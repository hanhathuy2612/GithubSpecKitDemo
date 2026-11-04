package com.example.resetpassword.service.auth;

import com.example.resetpassword.domain.RegistrationToken;
import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.mail.ResetMailDispatcher;
import com.example.resetpassword.ratelimit.RateLimiter;
import com.example.resetpassword.repository.RegistrationTokenRepository;
import com.example.resetpassword.repository.UserAccountRepository;
import com.example.resetpassword.security.TokenGenerator;
import com.example.resetpassword.service.AfterCommit;
import com.example.resetpassword.service.EmailNormalizer;
import com.example.resetpassword.service.PasswordPolicy;
import com.example.resetpassword.service.PasswordViolation;
import com.example.resetpassword.service.RequestOutcome;
import com.example.resetpassword.service.TokenStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 002 US2 (research R6, R8): register with the email only; the password is chosen on the page opened from the
 * emailed link and the account is created only then. This prevents pre-registration account takeover.
 */
@Service
public class RegistrationService {

    public static final Duration LINK_TTL = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserAccountRepository accounts;
    private final RegistrationTokenRepository tokens;
    private final TokenGenerator tokenGenerator;
    private final ResetMailDispatcher mail;
    private final RateLimiter rateLimiter;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final TransactionTemplate tx;

    public RegistrationService(UserAccountRepository accounts, RegistrationTokenRepository tokens,
            TokenGenerator tokenGenerator, ResetMailDispatcher mail, RateLimiter rateLimiter,
            PasswordPolicy passwordPolicy, PasswordEncoder passwordEncoder, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.tokenGenerator = tokenGenerator;
        this.mail = mail;
        this.rateLimiter = rateLimiter;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Always ACCEPTED: the caller cannot tell new, pending, active, disabled or throttled emails apart (FR-010). */
    public RequestOutcome register(String rawEmail, String clientIp) {
        String email = EmailNormalizer.normalize(rawEmail);
        String rawToken = tokenGenerator.generate();
        String tokenHash = tokenGenerator.hash(rawToken);

        boolean emailAllowed = rateLimiter.tryAcquire("register-email:" + email, 3, Duration.ofMinutes(15));
        boolean ipAllowed = rateLimiter.tryAcquire("register-ip:" + clientIp, 20, Duration.ofHours(1));
        if (!emailAllowed || !ipAllowed) {
            log.info("auth.registered outcome=rate_limited");
            return RequestOutcome.ACCEPTED;
        }

        tx.executeWithoutResult(status -> {
            Optional<UserAccount> account = accounts.findByEmail(email);
            Instant now = clock.instant();
            if (account.isEmpty()) {
                tokens.invalidateUnusedForEmail(email, now);
                tokens.save(new RegistrationToken(email, tokenHash, now, now.plus(LINK_TTL)));
                AfterCommit.run(() -> mail.sendRegistrationLink(email, rawToken));
                log.info("auth.registered outcome=link_sent");
            } else if (account.get().isActive()) {
                long id = account.get().getId();
                AfterCommit.run(() -> mail.sendAttemptedRegistration(id, email));
                log.info("auth.registered outcome=existing_notified accountId={}", id);
            } else {
                log.info("auth.registered outcome=ignored accountId={}", account.get().getId());
            }
        });
        return RequestOutcome.ACCEPTED;
    }

    /** Read-only: opening the link never consumes it (mail scanners). */
    public TokenStatus checkLink(String rawToken) {
        return resolveUsable(rawToken).isPresent() ? TokenStatus.USABLE : TokenStatus.INVALID_OR_EXPIRED;
    }

    public RegistrationOutcome completeRegistration(String rawToken, String password, String confirmPassword) {
        Optional<RegistrationToken> token = resolveUsable(rawToken);
        if (token.isEmpty()) {
            return RegistrationOutcome.INVALID;
        }
        List<PasswordViolation> violations = passwordPolicy.check(password, confirmPassword, null);
        if (!violations.isEmpty()) {
            return new RegistrationOutcome.Rejected(violations);
        }
        String hash = passwordEncoder.encode(password);
        try {
            UserAccount created = tx.execute(status -> {
                Instant now = clock.instant();
                if (tokens.markUsedIfUsable(token.get().getId(), now) != 1) {
                    status.setRollbackOnly();
                    return null;
                }
                return accounts.save(UserAccount.active(token.get().getEmail(), hash, now));
            });
            if (created == null) {
                log.info("auth.registration_completed outcome=invalid reason=already_used");
                return RegistrationOutcome.INVALID;
            }
            log.info("auth.registration_completed outcome=success accountId={}", created.getId());
            return new RegistrationOutcome.Completed();
        } catch (DataAccessException | TransactionException ex) {
            log.info("auth.registration_completed outcome=invalid reason=conflict error={}",
                    ex.getClass().getSimpleName());
            return RegistrationOutcome.INVALID;
        }
    }

    private Optional<RegistrationToken> resolveUsable(String rawToken) {
        if (!tokenGenerator.isWellFormed(rawToken)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return tokens.findByTokenHash(tokenGenerator.hash(rawToken)).filter(t -> t.isUsable(now));
    }
}

package com.example.resetpassword.service;

import com.example.resetpassword.domain.PasswordResetToken;
import com.example.resetpassword.domain.RevokeReason;
import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.mail.ResetMailDispatcher;
import com.example.resetpassword.ratelimit.RateLimiter;
import com.example.resetpassword.repository.PasswordResetTokenRepository;
import com.example.resetpassword.repository.UserAccountRepository;
import com.example.resetpassword.security.TokenGenerator;
import com.example.resetpassword.service.auth.AuthSessionService;
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
 * All password-reset business rules live here (Constitution II). Controllers only translate HTTP to these calls.
 * Logs carry account ids and outcomes only, never emails, tokens or passwords (FR-015).
 */
@Service
public class PasswordResetService {

    public static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    public static final int EMAIL_LIMIT = 3;
    public static final Duration EMAIL_WINDOW = Duration.ofMinutes(15);
    public static final int IP_LIMIT = 20;
    public static final Duration IP_WINDOW = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserAccountRepository accounts;
    private final PasswordResetTokenRepository tokens;
    private final TokenGenerator tokenGenerator;
    private final ResetMailDispatcher mail;
    private final Clock clock;
    private final RateLimiter rateLimiter;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessions;
    private final TransactionTemplate tx;

    public PasswordResetService(UserAccountRepository accounts, PasswordResetTokenRepository tokens,
            TokenGenerator tokenGenerator, ResetMailDispatcher mail, Clock clock, RateLimiter rateLimiter, PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder, AuthSessionService authSessions,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.tokenGenerator = tokenGenerator;
        this.mail = mail;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.authSessions = authSessions;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Issues a reset link for an ACTIVE account. Always returns {@link RequestOutcome#ACCEPTED}, so the caller's
     * response cannot reveal whether the account exists (FR-005).
     */
    public RequestOutcome requestReset(String rawEmail, String clientIp) {
        String email = EmailNormalizer.normalize(rawEmail);
        // Same CPU work on every path (research R5): the token is generated and hashed before the lookup.
        String rawToken = tokenGenerator.generate();
        String tokenHash = tokenGenerator.hash(rawToken);

        // FR-014: checked before the lookup so unknown emails consume slots too; both rules are always evaluated.
        boolean emailAllowed = rateLimiter.tryAcquire("email:" + email, EMAIL_LIMIT, EMAIL_WINDOW);
        boolean ipAllowed = rateLimiter.tryAcquire("ip:" + clientIp, IP_LIMIT, IP_WINDOW);
        if (!emailAllowed || !ipAllowed) {
            log.info("password_reset.requested outcome=rate_limited");
            return RequestOutcome.ACCEPTED;
        }

        tx.executeWithoutResult(status -> {
            Optional<UserAccount> account = accounts.findByEmail(email).filter(UserAccount::isActive);
            if (account.isEmpty()) {
                log.info("password_reset.requested outcome=unknown_or_inactive");
                return;
            }
            UserAccount owner = account.get();
            Instant now = clock.instant();
            tokens.invalidateUnusedForAccount(owner.getId(), now);
            tokens.save(new PasswordResetToken(owner.getId(), tokenHash, now, now.plus(TOKEN_TTL)));
            log.info("password_reset.requested outcome=issued accountId={}", owner.getId());
            AfterCommit.run(() -> mail.sendResetLink(owner.getId(), owner.getEmail(), rawToken));
        });
        return RequestOutcome.ACCEPTED;
    }

    /** Read-only: opening the link never consumes it (research R11, email link scanners). */
    public TokenStatus checkToken(String rawToken) {
        return resolveUsable(rawToken).isPresent() ? TokenStatus.USABLE : TokenStatus.INVALID_OR_EXPIRED;
    }

    /**
     * Sets a new password. Token checks run before password rules; password-rule failures do not consume the token;
     * the password update and marking the token used happen atomically (FR-013, research R8).
     */
    public ResetOutcome resetPassword(String rawToken, String newPassword, String confirmPassword) {
        Optional<Resolved> resolved = resolveUsable(rawToken);
        if (resolved.isEmpty()) {
            return ResetOutcome.INVALID_OR_EXPIRED;
        }
        PasswordResetToken token = resolved.get().token();
        UserAccount account = resolved.get().account();

        List<PasswordViolation> violations =
                passwordPolicy.check(newPassword, confirmPassword, account.getPasswordHash());
        if (!violations.isEmpty()) {
            log.info("password_reset.rejected reason=password_policy accountId={} violations={}",
                    account.getId(), violations);
            return new ResetOutcome.Rejected(violations);
        }
        // Hash before the write transaction so the row lock is held only briefly.
        String newHash = passwordEncoder.encode(newPassword);

        try {
            Boolean consumed = tx.execute(status -> {
                Instant now = clock.instant();
                if (tokens.markUsedIfUsable(token.getId(), now) != 1) {
                    status.setRollbackOnly();
                    return false;
                }
                accounts.updatePasswordHash(account.getId(), newHash, now);
                // 002 FR-017 (amends 001 FR-013): sign out everywhere, invalidate every access token.
                authSessions.revokeAllForAccount(account.getId(), RevokeReason.PASSWORD_RESET);
                AfterCommit.run(() -> mail.sendPasswordChanged(account.getId(), account.getEmail(), now));
                return true;
            });
            if (Boolean.TRUE.equals(consumed)) {
                log.info("password_reset.completed accountId={}", account.getId());
                return new ResetOutcome.Success();
            }
            log.info("password_reset.rejected reason=already_consumed accountId={}", account.getId());
        } catch (DataAccessException | TransactionException ex) {
            // A concurrent submit holding or having consumed the same token (research R8).
            log.info("password_reset.rejected reason=concurrent_update accountId={} error={}",
                    account.getId(), ex.getClass().getSimpleName());
        }
        return ResetOutcome.INVALID_OR_EXPIRED;
    }

    private record Resolved(PasswordResetToken token, UserAccount account) {
    }

    private Optional<Resolved> resolveUsable(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            log.info("password_reset.rejected reason=missing");
            return Optional.empty();
        }
        if (!tokenGenerator.isWellFormed(rawToken)) {
            // Malformed input never reaches the database; reported like any other invalid link (FR-012).
            log.info("password_reset.rejected reason=malformed");
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<PasswordResetToken> token = tokens.findByTokenHash(tokenGenerator.hash(rawToken));
        if (token.isEmpty()) {
            log.info("password_reset.rejected reason=unknown");
            return Optional.empty();
        }
        if (!token.get().isUsable(now)) {
            log.info("password_reset.rejected reason={} accountId={}", rejectionReason(token.get(), now),
                    token.get().getAccountId());
            return Optional.empty();
        }
        Optional<UserAccount> account = accounts.findById(token.get().getAccountId()).filter(UserAccount::isActive);
        if (account.isEmpty()) {
            log.info("password_reset.rejected reason=inactive_account accountId={}", token.get().getAccountId());
            return Optional.empty();
        }
        return Optional.of(new Resolved(token.get(), account.get()));
    }

    private static String rejectionReason(PasswordResetToken token, Instant now) {
        if (token.getUsedAt() != null) {
            return "used";
        }
        if (token.getInvalidatedAt() != null) {
            return "invalidated";
        }
        return "expired";
    }
}

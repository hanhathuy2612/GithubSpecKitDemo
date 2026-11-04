package com.example.resetpassword.service;

import com.example.resetpassword.domain.PasswordResetToken;
import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.mail.ResetMailDispatcher;
import com.example.resetpassword.repository.PasswordResetTokenRepository;
import com.example.resetpassword.repository.UserAccountRepository;
import com.example.resetpassword.security.TokenGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * All password-reset business rules live here (Constitution II). Controllers only translate HTTP to these calls.
 * Logs carry account ids and outcomes only, never emails, tokens or passwords (FR-015).
 */
@Service
public class PasswordResetService {

    public static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserAccountRepository accounts;
    private final PasswordResetTokenRepository tokens;
    private final TokenGenerator tokenGenerator;
    private final ResetMailDispatcher mail;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PasswordResetService(UserAccountRepository accounts, PasswordResetTokenRepository tokens,
            TokenGenerator tokenGenerator, ResetMailDispatcher mail, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.tokenGenerator = tokenGenerator;
        this.mail = mail;
        this.clock = clock;
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
}

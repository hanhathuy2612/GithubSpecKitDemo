package com.example.resetpassword.service.auth;

import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.mail.ResetMailDispatcher;
import com.example.resetpassword.ratelimit.RateLimiter;
import com.example.resetpassword.repository.UserAccountRepository;
import com.example.resetpassword.service.AfterCommit;
import com.example.resetpassword.service.PasswordPolicy;
import com.example.resetpassword.service.PasswordViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 002 US4 / research R9: change password while signed in. */
@Service
public class PasswordChangeService {

    public static final int FAILURE_LIMIT = 5;
    public static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final UserAccountRepository accounts;
    private final AuthSessionService sessions;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final ResetMailDispatcher mail;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PasswordChangeService(UserAccountRepository accounts, AuthSessionService sessions,
            PasswordPolicy passwordPolicy, PasswordEncoder passwordEncoder, RateLimiter rateLimiter,
            ResetMailDispatcher mail, Clock clock, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.mail = mail;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public PasswordChangeOutcome changePassword(long accountId, String sessionId, String currentPassword,
            String newPassword, String confirmPassword) {
        Optional<UserAccount> found = accounts.findById(accountId).filter(UserAccount::isActive);
        if (found.isEmpty()) {
            return PasswordChangeOutcome.INVALID_SESSION;
        }
        UserAccount account = found.get();
        String failureKey = "pwchange:" + accountId;
        if (rateLimiter.isLimited(failureKey, FAILURE_LIMIT, FAILURE_WINDOW)) {
            log.info("auth.password_changed outcome=too_many_attempts accountId={}", accountId);
            return PasswordChangeOutcome.TOO_MANY_ATTEMPTS;
        }
        boolean currentOk = currentPassword != null && PasswordPolicy.fitsMaxLength(currentPassword)
                && passwordEncoder.matches(currentPassword, account.getPasswordHash());
        if (!currentOk) {
            rateLimiter.record(failureKey);
            log.info("auth.password_changed outcome=wrong_current accountId={}", accountId);
            return PasswordChangeOutcome.WRONG_CURRENT_PASSWORD;
        }
        List<PasswordViolation> violations =
                passwordPolicy.check(newPassword, confirmPassword, account.getPasswordHash());
        if (!violations.isEmpty()) {
            return new PasswordChangeOutcome.Rejected(violations);
        }
        String newHash = passwordEncoder.encode(newPassword);
        int newVersion = account.getSessionVersion() + 1;

        PasswordChangeOutcome outcome = tx.execute(status -> {
            Instant now = clock.instant();
            accounts.updatePasswordHash(accountId, newHash, now);
            accounts.bumpSessionVersion(accountId);
            sessions.revokeOtherSessions(accountId, sessionId);
            Optional<IssuedCredentials> fresh = sessions.reissueAfterPasswordChange(sessionId, accountId, newVersion);
            if (fresh.isEmpty()) {
                status.setRollbackOnly();
                return PasswordChangeOutcome.INVALID_SESSION;
            }
            AfterCommit.run(() -> mail.sendPasswordChanged(accountId, account.getEmail(), now));
            return new PasswordChangeOutcome.Changed(fresh.get());
        });
        log.info("auth.password_changed outcome={} accountId={}",
                outcome instanceof PasswordChangeOutcome.Changed ? "success" : "invalid_session", accountId);
        return outcome;
    }
}

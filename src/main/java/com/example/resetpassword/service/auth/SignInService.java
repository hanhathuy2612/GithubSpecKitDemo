package com.example.resetpassword.service.auth;

import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.ratelimit.SignInThrottle;
import com.example.resetpassword.repository.UserAccountRepository;
import com.example.resetpassword.service.EmailNormalizer;
import com.example.resetpassword.service.PasswordPolicy;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Sign-in (002 US1). Exactly one password-hash comparison per evaluated attempt, against a dummy hash for unknown
 * emails, so timing does not reveal whether an account exists (research R6).
 */
@Service
public class SignInService {

    private static final Logger log = LoggerFactory.getLogger(SignInService.class);

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final SignInThrottle throttle;
    private final AuthSessionService sessions;
    private final String dummyHash;

    public SignInService(UserAccountRepository accounts, PasswordEncoder passwordEncoder, SignInThrottle throttle,
            AuthSessionService sessions) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.sessions = sessions;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        this.dummyHash = passwordEncoder.encode(HexFormat.of().formatHex(random));
    }

    public SignInOutcome signIn(String rawEmail, String password, String clientIp) {
        String email = EmailNormalizer.normalize(rawEmail);
        if (!throttle.allowed(email, clientIp)) {
            log.info("auth.sign_in outcome=failure reason=throttled");
            return SignInOutcome.FAILED;
        }
        Optional<UserAccount> account = accounts.findByEmail(email);
        String hash = account.map(UserAccount::getPasswordHash).orElse(dummyHash);
        boolean acceptable = password != null && PasswordPolicy.fitsMaxLength(password);
        // Always exactly one comparison; over-long input compares a fixed string so it still costs the same.
        boolean matches = passwordEncoder.matches(acceptable ? password : "-", hash) && acceptable;

        if (account.isPresent() && account.get().isActive() && matches) {
            throttle.success(email);
            log.info("auth.sign_in outcome=success accountId={}", account.get().getId());
            return new SignInOutcome.Success(sessions.startSession(account.get()));
        }
        throttle.failure(email, clientIp);
        log.info("auth.sign_in outcome=failure reason={}", account.isEmpty() ? "unknown" : "rejected");
        return SignInOutcome.FAILED;
    }
}

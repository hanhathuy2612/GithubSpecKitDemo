package com.example.resetpassword.service.auth;

import com.example.resetpassword.domain.AuthSession;
import com.example.resetpassword.domain.RefreshToken;
import com.example.resetpassword.domain.RevokeReason;
import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.repository.AuthSessionRepository;
import com.example.resetpassword.repository.RefreshTokenRepository;
import com.example.resetpassword.repository.UserAccountRepository;
import com.example.resetpassword.security.AccessTokenIssuer;
import com.example.resetpassword.security.TokenGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Sessions, refresh-token rotation and revocation (002 research R4, R5). */
@Service
public class AuthSessionService {

    public static final Duration REFRESH_IDLE = Duration.ofDays(7);
    public static final Duration SESSION_MAX = Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(AuthSessionService.class);

    private final AuthSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final UserAccountRepository accounts;
    private final TokenGenerator tokenGenerator;
    private final AccessTokenIssuer accessTokens;
    private final Clock clock;
    private final TransactionTemplate tx;

    public AuthSessionService(AuthSessionRepository sessions, RefreshTokenRepository refreshTokens,
            UserAccountRepository accounts, TokenGenerator tokenGenerator, AccessTokenIssuer accessTokens,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
        this.tokenGenerator = tokenGenerator;
        this.accessTokens = accessTokens;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Creates a session (30-day cap) with its first refresh token (7 days, capped) and an access token. */
    public IssuedCredentials startSession(UserAccount account) {
        return tx.execute(status -> {
            Instant now = clock.instant();
            AuthSession session = sessions.save(new AuthSession(UUID.randomUUID().toString(), account.getId(), now,
                    now.plus(SESSION_MAX)));
            return issuePair(session, account.getId(), account.getSessionVersion(), now);
        });
    }

    /** Revokes every active session of the account and bumps its session version (FR-015a, FR-017). */
    public void revokeAllForAccount(long accountId, RevokeReason reason) {
        tx.executeWithoutResult(status -> {
            int revoked = sessions.revokeAllForAccount(accountId, clock.instant(), reason);
            accounts.bumpSessionVersion(accountId);
            log.info("auth.sessions_revoked reason={} count={} accountId={}", reason, revoked, accountId);
        });
    }

    IssuedCredentials issuePair(AuthSession session, long accountId, int sessionVersion, Instant now) {
        String raw = tokenGenerator.generate();
        Instant expiresAt = min(now.plus(REFRESH_IDLE), session.getAbsoluteExpiresAt());
        refreshTokens.save(new RefreshToken(session.getId(), tokenGenerator.hash(raw), now, expiresAt));
        AccessTokenIssuer.AccessToken access = accessTokens.issue(accountId, session.getId(), sessionVersion);
        return new IssuedCredentials(access.value(), access.expiresIn(), raw, expiresAt);
    }

    static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}

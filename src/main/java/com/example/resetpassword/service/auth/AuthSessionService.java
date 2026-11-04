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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
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

    /**
     * Research R4: rotate on success; a rotated token presented again (including the loser of a concurrent double
     * refresh) revokes the whole session. Every failure is reported as empty.
     */
    public Optional<IssuedCredentials> refresh(String rawRefreshToken) {
        if (!tokenGenerator.isWellFormed(rawRefreshToken)) {
            log.info("auth.refresh outcome=invalid reason=malformed");
            return Optional.empty();
        }
        String hash = tokenGenerator.hash(rawRefreshToken);
        try {
            return tx.execute(status -> refreshInTransaction(hash));
        } catch (DataAccessException | TransactionException ex) {
            // Lost a concurrent rotation at the database level: same handling as reuse.
            refreshTokens.findByTokenHash(hash).ifPresent(token -> revokeForReuse(token.getSessionId()));
            log.info("auth.refresh outcome=reuse_revoked reason=concurrent error={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<IssuedCredentials> refreshInTransaction(String hash) {
        Instant now = clock.instant();
        Optional<RefreshToken> found = refreshTokens.findByTokenHash(hash);
        if (found.isEmpty()) {
            log.info("auth.refresh outcome=invalid reason=unknown");
            return Optional.empty();
        }
        RefreshToken token = found.get();
        AuthSession session = sessions.findById(token.getSessionId()).orElse(null);
        if (session == null || session.isRevoked()) {
            log.info("auth.refresh outcome=invalid reason=revoked sessionId={}", token.getSessionId());
            return Optional.empty();
        }
        if (token.isRotated()) {
            sessions.revoke(session.getId(), now, RevokeReason.REUSE);
            log.info("auth.refresh outcome=reuse_revoked sessionId={}", session.getId());
            return Optional.empty();
        }
        if (!now.isBefore(token.getExpiresAt())) {
            log.info("auth.refresh outcome=invalid reason=expired sessionId={}", session.getId());
            return Optional.empty();
        }
        Optional<UserAccount> account = accounts.findById(session.getAccountId()).filter(UserAccount::isActive);
        if (account.isEmpty()) {
            sessions.revoke(session.getId(), now, RevokeReason.DISABLED);
            log.info("auth.refresh outcome=invalid reason=inactive_account sessionId={}", session.getId());
            return Optional.empty();
        }
        if (refreshTokens.markRotatedIfCurrent(token.getId(), now) != 1) {
            sessions.revoke(session.getId(), now, RevokeReason.REUSE);
            log.info("auth.refresh outcome=reuse_revoked reason=race sessionId={}", session.getId());
            return Optional.empty();
        }
        log.info("auth.refresh outcome=success sessionId={}", session.getId());
        return Optional.of(issuePair(session, account.get().getId(), account.get().getSessionVersion(), now));
    }

    private void revokeForReuse(String sessionId) {
        tx.executeWithoutResult(status -> sessions.revoke(sessionId, clock.instant(), RevokeReason.REUSE));
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

package com.example.resetpassword.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.resetpassword.domain.AuthSession;
import com.example.resetpassword.domain.RefreshToken;
import com.example.resetpassword.domain.RevokeReason;
import com.example.resetpassword.repository.AuthSessionRepository;
import com.example.resetpassword.repository.RefreshTokenRepository;
import com.example.resetpassword.security.AccessTokenIssuer;
import com.example.resetpassword.service.auth.AuthSessionService;
import com.example.resetpassword.service.auth.IssuedCredentials;
import com.example.resetpassword.support.ServiceFixtures;
import com.example.resetpassword.support.TestFixtures;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthSessionServiceTest {

    private ServiceFixtures fx;
    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final AccessTokenIssuer issuer = mock(AccessTokenIssuer.class);
    private AuthSessionService service;
    private AuthSession session;
    private String raw;
    private RefreshToken token;

    @BeforeEach
    void setUp() {
        fx = new ServiceFixtures();
        fx.account(1L, TestFixtures.ALICE, true);
        service = new AuthSessionService(sessions, refreshTokens, fx.accounts, fx.tokenGenerator, issuer, fx.clock,
                fx.transactionManager());
        when(issuer.issue(anyLong(), anyString(), anyInt()))
                .thenReturn(new AccessTokenIssuer.AccessToken("access.jwt", 900));
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
        session = new AuthSession("s-1", 1L, TestFixtures.START, TestFixtures.START.plus(Duration.ofDays(30)));
        when(sessions.findById("s-1")).thenReturn(Optional.of(session));
        raw = fx.tokenGenerator.generate();
        token = new RefreshToken("s-1", fx.tokenGenerator.hash(raw), TestFixtures.START,
                TestFixtures.START.plus(Duration.ofDays(7)));
        ServiceFixtures.set(token, "id", 5L);
        when(refreshTokens.findByTokenHash(fx.tokenGenerator.hash(raw))).thenReturn(Optional.of(token));
        when(refreshTokens.markRotatedIfCurrent(eq(5L), any())).thenReturn(1);
    }

    @Test
    void startSessionStoresOnlyHashesAndCapsRefreshExpiry() {
        IssuedCredentials creds = service.startSession(fx.accounts.findById(1L).orElseThrow());

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(saved.capture());
        assertThat(saved.getValue().getTokenHash()).isEqualTo(fx.tokenGenerator.hash(creds.refreshToken()))
                .isNotEqualTo(creds.refreshToken());
        assertThat(creds.refreshExpiresAt()).isEqualTo(TestFixtures.START.plus(Duration.ofDays(7)));
        assertThat(creds.accessToken()).isEqualTo("access.jwt");
    }

    @Test
    void refreshRotates() {
        Optional<IssuedCredentials> next = service.refresh(raw);

        assertThat(next).isPresent();
        assertThat(next.get().refreshToken()).isNotEqualTo(raw);
        verify(refreshTokens).markRotatedIfCurrent(5L, TestFixtures.START);
        verify(sessions, never()).revoke(anyString(), any(), any());
    }

    @Test
    void reusingARotatedTokenRevokesTheSession() {
        ServiceFixtures.set(token, "rotatedAt", TestFixtures.START);

        assertThat(service.refresh(raw)).isEmpty();
        verify(sessions).revoke("s-1", TestFixtures.START, RevokeReason.REUSE);
    }

    @Test
    void losingTheRotationRaceCountsAsReuse() {
        when(refreshTokens.markRotatedIfCurrent(eq(5L), any())).thenReturn(0);

        assertThat(service.refresh(raw)).isEmpty();
        verify(sessions).revoke("s-1", TestFixtures.START, RevokeReason.REUSE);
    }

    @Test
    void idleForSevenDaysIsInvalid() {
        fx.clock.advance(Duration.ofDays(7));
        assertThat(service.refresh(raw)).isEmpty();
    }

    @Test
    void refreshOnDay29IsCappedAtThirtyDaysAndRejectedAtTheCap() {
        fx.clock.setInstant(TestFixtures.START.plus(Duration.ofDays(29)));
        token = new RefreshToken("s-1", fx.tokenGenerator.hash(raw), fx.clock.instant(),
                TestFixtures.START.plus(Duration.ofDays(30)));
        ServiceFixtures.set(token, "id", 5L);
        when(refreshTokens.findByTokenHash(fx.tokenGenerator.hash(raw))).thenReturn(Optional.of(token));

        IssuedCredentials next = service.refresh(raw).orElseThrow();
        assertThat(next.refreshExpiresAt()).isEqualTo(TestFixtures.START.plus(Duration.ofDays(30)));

        fx.clock.setInstant(TestFixtures.START.plus(Duration.ofDays(30)));
        assertThat(service.refresh(raw)).isEmpty();
    }

    @Test
    void revokedSessionIsInvalid() {
        ServiceFixtures.set(session, "revokedAt", TestFixtures.START);
        assertThat(service.refresh(raw)).isEmpty();
    }

    @Test
    void disabledAccountIsInvalid() {
        fx.account(1L, TestFixtures.ALICE, false);
        assertThat(service.refresh(raw)).isEmpty();
    }

    @Test
    void malformedTokenIsInvalidWithoutLookup() {
        assertThat(service.refresh("garbage")).isEmpty();
        verify(refreshTokens, never()).findByTokenHash(anyString());
    }
}

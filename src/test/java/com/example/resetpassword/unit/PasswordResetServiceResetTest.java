package com.example.resetpassword.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.resetpassword.domain.PasswordResetToken;
import com.example.resetpassword.service.PasswordViolation;
import com.example.resetpassword.service.ResetOutcome;
import com.example.resetpassword.service.TokenStatus;
import com.example.resetpassword.support.ServiceFixtures;
import com.example.resetpassword.support.TestFixtures;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PasswordResetServiceResetTest {

    private ServiceFixtures fx;
    private String rawToken;
    private PasswordResetToken token;

    @BeforeEach
    void setUp() {
        fx = new ServiceFixtures();
        fx.account(1L, TestFixtures.ALICE, true);
        rawToken = fx.tokenGenerator.generate();
        token = new PasswordResetToken(1L, fx.tokenGenerator.hash(rawToken), TestFixtures.START,
                TestFixtures.START.plus(Duration.ofMinutes(15)));
        ServiceFixtures.set(token, "id", 10L);
        when(fx.tokens.findByTokenHash(fx.tokenGenerator.hash(rawToken))).thenReturn(Optional.of(token));
        when(fx.tokens.markUsedIfUsable(eq(10L), any())).thenReturn(1);
    }

    @Test
    void successUpdatesHashMarksUsedAndSendsNotification() {
        ResetOutcome outcome = fx.service().resetPassword(rawToken, "brand-new-pass", "brand-new-pass");

        assertThat(outcome).isInstanceOf(ResetOutcome.Success.class);
        verify(fx.tokens).markUsedIfUsable(10L, TestFixtures.START);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(fx.accounts).updatePasswordHash(eq(1L), hash.capture(), eq(TestFixtures.START));
        assertThat(hash.getValue()).startsWith("$2");
        assertThat(fx.passwordEncoder.matches("brand-new-pass", hash.getValue())).isTrue();
        verify(fx.mail).sendPasswordChanged(1L, TestFixtures.ALICE, TestFixtures.START);
        verify(fx.authSessions).revokeAllForAccount(1L, com.example.resetpassword.domain.RevokeReason.PASSWORD_RESET);
    }

    @Test
    void policyFailureDoesNotConsumeTokenOrChangePassword() {
        ResetOutcome outcome = fx.service().resetPassword(rawToken, "short", "short");

        assertThat(outcome).isEqualTo(new ResetOutcome.Rejected(java.util.List.of(PasswordViolation.TOO_SHORT)));
        verify(fx.tokens, never()).markUsedIfUsable(anyLong(), any());
        verify(fx.accounts, never()).updatePasswordHash(anyLong(), anyString(), any());
        verify(fx.mail, never()).sendPasswordChanged(anyLong(), anyString(), any());
        verify(fx.authSessions, never()).revokeAllForAccount(anyLong(), any());
    }

    @Test
    void lostRaceOnConditionalUpdateIsInvalid() {
        when(fx.tokens.markUsedIfUsable(eq(10L), any())).thenReturn(0);

        ResetOutcome outcome = fx.service().resetPassword(rawToken, "brand-new-pass", "brand-new-pass");

        assertThat(outcome).isEqualTo(ResetOutcome.INVALID_OR_EXPIRED);
        verify(fx.accounts, never()).updatePasswordHash(anyLong(), anyString(), any());
        verify(fx.mail, never()).sendPasswordChanged(anyLong(), anyString(), any());
    }

    @Test
    void checkTokenIsReadOnly() {
        assertThat(fx.service().checkToken(rawToken)).isEqualTo(TokenStatus.USABLE);
        verify(fx.tokens, never()).markUsedIfUsable(anyLong(), any());
    }
}

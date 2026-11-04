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
import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.service.RequestOutcome;
import com.example.resetpassword.support.ServiceFixtures;
import com.example.resetpassword.support.TestFixtures;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PasswordResetServiceRequestTest {

    private ServiceFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new ServiceFixtures();
    }

    @Test
    void activeAccountGetsHashedTokenExpiringIn15MinutesAndOneEmail() {
        UserAccount alice = fx.account(1L, TestFixtures.ALICE, true);

        RequestOutcome outcome = fx.service().requestReset(TestFixtures.ALICE, "10.0.0.1");

        assertThat(outcome).isEqualTo(RequestOutcome.ACCEPTED);
        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(fx.tokens).save(saved.capture());
        PasswordResetToken token = saved.getValue();
        assertThat(token.getAccountId()).isEqualTo(1L);
        assertThat(token.getIssuedAt()).isEqualTo(TestFixtures.START);
        assertThat(token.getExpiresAt()).isEqualTo(TestFixtures.START.plus(Duration.ofMinutes(15)));

        ArgumentCaptor<String> rawToken = ArgumentCaptor.forClass(String.class);
        verify(fx.mail).sendResetLink(eq(1L), eq(alice.getEmail()), rawToken.capture());
        assertThat(token.getTokenHash())
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(rawToken.getValue())
                .isEqualTo(fx.tokenGenerator.hash(rawToken.getValue()));
    }

    @Test
    void emailIsNormalizedBeforeLookup() {
        fx.account(1L, TestFixtures.ALICE, true);

        fx.service().requestReset("  A" + TestFixtures.ALICE.substring(1).toUpperCase() + " ", "10.0.0.1");

        verify(fx.accounts).findByEmail(TestFixtures.ALICE);
        verify(fx.mail).sendResetLink(eq(1L), eq(TestFixtures.ALICE), anyString());
    }

    @Test
    void newRequestInvalidatesPreviousUnusedTokens() {
        fx.account(1L, TestFixtures.ALICE, true);

        fx.service().requestReset(TestFixtures.ALICE, "10.0.0.1");

        verify(fx.tokens).invalidateUnusedForAccount(1L, TestFixtures.START);
    }

    @Test
    void disabledAccountGetsNoTokenAndNoEmail() {
        fx.account(3L, TestFixtures.DISABLED, false);

        RequestOutcome outcome = fx.service().requestReset(TestFixtures.DISABLED, "10.0.0.1");

        assertThat(outcome).isEqualTo(RequestOutcome.ACCEPTED);
        verify(fx.tokens, never()).save(any());
        verify(fx.mail, never()).sendResetLink(anyLong(), anyString(), anyString());
    }

    @Test
    void unknownEmailGetsNoTokenAndNoEmail() {
        when(fx.accounts.findByEmail(TestFixtures.NOBODY)).thenReturn(Optional.empty());

        RequestOutcome outcome = fx.service().requestReset(TestFixtures.NOBODY, "10.0.0.1");

        assertThat(outcome).isEqualTo(RequestOutcome.ACCEPTED);
        verify(fx.tokens, never()).save(any());
        verify(fx.mail, never()).sendResetLink(anyLong(), anyString(), anyString());
    }
}

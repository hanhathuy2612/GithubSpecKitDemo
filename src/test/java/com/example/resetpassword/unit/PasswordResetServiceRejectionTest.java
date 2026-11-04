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
import com.example.resetpassword.service.ResetOutcome;
import com.example.resetpassword.service.TokenStatus;
import com.example.resetpassword.support.ServiceFixtures;
import com.example.resetpassword.support.TestFixtures;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordResetServiceRejectionTest {

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

    private ResetOutcome reset() {
        return fx.service().resetPassword(rawToken, "brand-new-pass", "brand-new-pass");
    }

    private void assertPasswordUnchanged() {
        verify(fx.accounts, never()).updatePasswordHash(anyLong(), anyString(), any());
    }

    @Test
    void usableOneMillisecondBeforeFifteenMinutes() {
        fx.clock.setInstant(TestFixtures.START.plus(Duration.ofMinutes(15)).minusMillis(1));
        assertThat(fx.service().checkToken(rawToken)).isEqualTo(TokenStatus.USABLE);
    }

    @Test
    void expiredAtExactlyFifteenMinutes() {
        fx.clock.setInstant(TestFixtures.START.plus(Duration.ofMinutes(15)));

        assertThat(fx.service().checkToken(rawToken)).isEqualTo(TokenStatus.INVALID_OR_EXPIRED);
        assertThat(reset()).isEqualTo(ResetOutcome.INVALID_OR_EXPIRED);
        assertPasswordUnchanged();
    }

    @Test
    void usedTokenIsInvalid() {
        ServiceFixtures.set(token, "usedAt", TestFixtures.START);

        assertThat(reset()).isEqualTo(ResetOutcome.INVALID_OR_EXPIRED);
        assertPasswordUnchanged();
    }

    @Test
    void invalidatedTokenIsInvalid() {
        ServiceFixtures.set(token, "invalidatedAt", TestFixtures.START);

        assertThat(reset()).isEqualTo(ResetOutcome.INVALID_OR_EXPIRED);
        assertPasswordUnchanged();
    }

    @Test
    void unknownTokenIsInvalid() {
        String other = fx.tokenGenerator.generate();
        when(fx.tokens.findByTokenHash(fx.tokenGenerator.hash(other))).thenReturn(Optional.empty());

        assertThat(fx.service().resetPassword(other, "brand-new-pass", "brand-new-pass"))
                .isEqualTo(ResetOutcome.INVALID_OR_EXPIRED);
        assertPasswordUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "+++++++++++++++++++++++++++++++++++++++++++",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void malformedTokenIsInvalidWithoutLookup(String malformed) {
        assertThat(fx.service().resetPassword(malformed, "brand-new-pass", "brand-new-pass"))
                .isEqualTo(ResetOutcome.INVALID_OR_EXPIRED);
        assertThat(fx.service().checkToken(malformed)).isEqualTo(TokenStatus.INVALID_OR_EXPIRED);
        verify(fx.tokens, never()).findByTokenHash(anyString());
        assertPasswordUnchanged();
    }

    @Test
    void invalidTokenWinsOverPasswordRuleErrors() {
        ServiceFixtures.set(token, "usedAt", TestFixtures.START);

        assertThat(fx.service().resetPassword(rawToken, "short", "other"))
                .isEqualTo(ResetOutcome.INVALID_OR_EXPIRED);
    }
}

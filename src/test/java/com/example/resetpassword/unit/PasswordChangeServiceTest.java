package com.example.resetpassword.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.resetpassword.ratelimit.InMemorySlidingWindowRateLimiter;
import com.example.resetpassword.service.PasswordPolicy;
import com.example.resetpassword.service.PasswordViolation;
import com.example.resetpassword.service.auth.AuthSessionService;
import com.example.resetpassword.service.auth.IssuedCredentials;
import com.example.resetpassword.service.auth.PasswordChangeOutcome;
import com.example.resetpassword.service.auth.PasswordChangeService;
import com.example.resetpassword.support.ServiceFixtures;
import com.example.resetpassword.support.TestFixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordChangeServiceTest {

    private ServiceFixtures fx;
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private PasswordEncoder encoder;
    private PasswordChangeService service;
    private final IssuedCredentials fresh = new IssuedCredentials("new.jwt", 900, "new-refresh", TestFixtures.START);

    @BeforeEach
    void setUp() {
        fx = new ServiceFixtures();
        encoder = spy(new BCryptPasswordEncoder(4));
        fx.account(1L, TestFixtures.ALICE, true, encoder.encode(TestFixtures.SEED_PASSWORD));
        when(sessions.reissueAfterPasswordChange(eq("s-1"), eq(1L), anyInt())).thenReturn(Optional.of(fresh));
        service = new PasswordChangeService(fx.accounts, sessions, new PasswordPolicy(encoder), encoder,
                new InMemorySlidingWindowRateLimiter(fx.clock), fx.mail, fx.clock, fx.transactionManager());
    }

    @Test
    void successChangesHashBumpsVersionRevokesOthersAndReissues() {
        PasswordChangeOutcome outcome = service.changePassword(1L, "s-1", TestFixtures.SEED_PASSWORD,
                "brand-new-pass", "brand-new-pass");

        assertThat(outcome).isEqualTo(new PasswordChangeOutcome.Changed(fresh));
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(fx.accounts).updatePasswordHash(eq(1L), hash.capture(), eq(TestFixtures.START));
        assertThat(encoder.matches("brand-new-pass", hash.getValue())).isTrue();
        verify(fx.accounts).bumpSessionVersion(1L);
        verify(sessions).revokeOtherSessions(1L, "s-1");
        verify(sessions).reissueAfterPasswordChange("s-1", 1L, 1);
        verify(fx.mail).sendPasswordChanged(1L, TestFixtures.ALICE, TestFixtures.START);
    }

    @Test
    void wrongCurrentPasswordChangesNothing() {
        assertThat(service.changePassword(1L, "s-1", "wrong-current", "brand-new-pass", "brand-new-pass"))
                .isEqualTo(PasswordChangeOutcome.WRONG_CURRENT_PASSWORD);
        verify(fx.accounts, never()).updatePasswordHash(anyLong(), anyString(), any());
    }

    @Test
    void sixthAttemptAfterFiveFailuresIsRefusedWithoutCheckingThePassword() {
        for (int i = 0; i < 5; i++) {
            service.changePassword(1L, "s-1", "wrong-current", "brand-new-pass", "brand-new-pass");
        }
        org.mockito.Mockito.clearInvocations(encoder);

        assertThat(service.changePassword(1L, "s-1", TestFixtures.SEED_PASSWORD, "brand-new-pass", "brand-new-pass"))
                .isEqualTo(PasswordChangeOutcome.TOO_MANY_ATTEMPTS);
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    void ruleFailuresChangeNothing() {
        assertThat(service.changePassword(1L, "s-1", TestFixtures.SEED_PASSWORD, "short", "short"))
                .isEqualTo(new PasswordChangeOutcome.Rejected(List.of(PasswordViolation.TOO_SHORT)));
        assertThat(service.changePassword(1L, "s-1", TestFixtures.SEED_PASSWORD, TestFixtures.SEED_PASSWORD,
                TestFixtures.SEED_PASSWORD))
                .isEqualTo(new PasswordChangeOutcome.Rejected(List.of(PasswordViolation.SAME_AS_CURRENT)));
        verify(fx.accounts, never()).updatePasswordHash(anyLong(), anyString(), any());
        verify(sessions, never()).revokeOtherSessions(anyLong(), anyString());
    }
}

package com.example.resetpassword.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.resetpassword.ratelimit.InMemorySlidingWindowRateLimiter;
import com.example.resetpassword.ratelimit.SignInThrottle;
import com.example.resetpassword.service.auth.AuthSessionService;
import com.example.resetpassword.service.auth.IssuedCredentials;
import com.example.resetpassword.service.auth.SignInOutcome;
import com.example.resetpassword.service.auth.SignInService;
import com.example.resetpassword.support.ServiceFixtures;
import com.example.resetpassword.support.TestFixtures;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class SignInServiceTest {

    private ServiceFixtures fx;
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private PasswordEncoder encoder;
    private SignInThrottle throttle;
    private SignInService service;

    @BeforeEach
    void setUp() {
        fx = new ServiceFixtures();
        encoder = spy(new BCryptPasswordEncoder(4));
        fx.account(1L, TestFixtures.ALICE, true, encoder.encode(TestFixtures.SEED_PASSWORD));
        fx.account(3L, TestFixtures.DISABLED, false, encoder.encode(TestFixtures.SEED_PASSWORD));
        when(fx.accounts.findByEmail(TestFixtures.NOBODY)).thenReturn(Optional.empty());
        throttle = new SignInThrottle(new InMemorySlidingWindowRateLimiter(fx.clock), fx.clock);
        service = new SignInService(fx.accounts, encoder, throttle, sessions);
        when(sessions.startSession(any())).thenReturn(new IssuedCredentials("a", 900, "r", TestFixtures.START));
    }

    @Test
    void successStartsSessionAndResetsThrottle() {
        throttle.failure(TestFixtures.ALICE, "10.0.0.1");
        fx.clock.advance(java.time.Duration.ofSeconds(1));

        SignInOutcome outcome = service.signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD, "10.0.0.1");

        assertThat(outcome).isInstanceOf(SignInOutcome.Success.class);
        throttle.failure(TestFixtures.ALICE, "10.0.0.1");
        fx.clock.advance(java.time.Duration.ofSeconds(1));
        assertThat(throttle.allowed(TestFixtures.ALICE, "10.0.0.1")).as("counter was reset to 1").isTrue();
    }

    @Test
    void everyFailureCauseIsTheSameOutcome() {
        assertThat(service.signIn(TestFixtures.ALICE, "wrong-password", "10.0.0.1")).isEqualTo(SignInOutcome.FAILED);
        assertThat(service.signIn(TestFixtures.NOBODY, "whatever-pass", "10.0.0.2")).isEqualTo(SignInOutcome.FAILED);
        assertThat(service.signIn(TestFixtures.DISABLED, TestFixtures.SEED_PASSWORD, "10.0.0.3"))
                .isEqualTo(SignInOutcome.FAILED);
        verify(sessions, never()).startSession(any());
    }

    @Test
    void unknownEmailStillRunsExactlyOneHashComparison() {
        org.mockito.Mockito.clearInvocations(encoder);
        service.signIn(TestFixtures.NOBODY, "whatever-pass", "10.0.0.1");
        verify(encoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void throttledAttemptSkipsThePasswordCheck() {
        throttle.failure(TestFixtures.ALICE, "10.0.0.1");
        org.mockito.Mockito.clearInvocations(encoder);

        assertThat(service.signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD, "10.0.0.1"))
                .isEqualTo(SignInOutcome.FAILED);
        verify(encoder, never()).matches(anyString(), anyString());
    }
}

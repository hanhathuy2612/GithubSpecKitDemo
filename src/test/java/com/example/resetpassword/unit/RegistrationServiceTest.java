package com.example.resetpassword.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.resetpassword.domain.RegistrationToken;
import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.repository.RegistrationTokenRepository;
import com.example.resetpassword.service.PasswordPolicy;
import com.example.resetpassword.service.PasswordViolation;
import com.example.resetpassword.service.RequestOutcome;
import com.example.resetpassword.service.TokenStatus;
import com.example.resetpassword.service.auth.RegistrationOutcome;
import com.example.resetpassword.service.auth.RegistrationService;
import com.example.resetpassword.support.ServiceFixtures;
import com.example.resetpassword.support.TestFixtures;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class RegistrationServiceTest {

    private static final String CAROL = "carol@example.com";

    private ServiceFixtures fx;
    private final RegistrationTokenRepository tokens = mock(RegistrationTokenRepository.class);
    private PasswordEncoder encoder;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        fx = new ServiceFixtures();
        encoder = spy(new BCryptPasswordEncoder(4));
        fx.account(1L, TestFixtures.ALICE, true);
        fx.account(3L, TestFixtures.DISABLED, false);
        when(fx.accounts.findByEmail(CAROL)).thenReturn(Optional.empty());
        when(tokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fx.accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new RegistrationService(fx.accounts, tokens, fx.tokenGenerator, fx.mail, fx.rateLimiter,
                new PasswordPolicy(encoder), encoder, fx.clock, fx.transactionManager());
    }

    private RegistrationToken stored(String raw, String email) {
        RegistrationToken token = new RegistrationToken(email, fx.tokenGenerator.hash(raw), TestFixtures.START,
                TestFixtures.START.plus(Duration.ofHours(24)));
        ServiceFixtures.set(token, "id", 9L);
        when(tokens.findByTokenHash(fx.tokenGenerator.hash(raw))).thenReturn(Optional.of(token));
        return token;
    }

    @Test
    void newEmailGetsHashedLinkAndNoAccount() {
        assertThat(service.register(" Carol@Example.com ", "10.0.0.1")).isEqualTo(RequestOutcome.ACCEPTED);

        verify(tokens).invalidateUnusedForEmail(CAROL, TestFixtures.START);
        ArgumentCaptor<RegistrationToken> saved = ArgumentCaptor.forClass(RegistrationToken.class);
        verify(tokens).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(TestFixtures.START.plus(Duration.ofHours(24)));
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(fx.mail).sendRegistrationLink(eq(CAROL), raw.capture());
        assertThat(saved.getValue().getTokenHash()).isEqualTo(fx.tokenGenerator.hash(raw.getValue()))
                .isNotEqualTo(raw.getValue());
        verify(fx.accounts, never()).save(any());
    }

    @Test
    void activeEmailGetsNoticeOnly() {
        assertThat(service.register(TestFixtures.ALICE, "10.0.0.1")).isEqualTo(RequestOutcome.ACCEPTED);
        verify(fx.mail).sendAttemptedRegistration(1L, TestFixtures.ALICE);
        verify(tokens, never()).save(any());
    }

    @Test
    void disabledEmailGetsNothing() {
        assertThat(service.register(TestFixtures.DISABLED, "10.0.0.1")).isEqualTo(RequestOutcome.ACCEPTED);
        verify(tokens, never()).save(any());
        verify(fx.mail, never()).sendAttemptedRegistration(anyLong(), anyString());
        verify(fx.mail, never()).sendRegistrationLink(anyString(), anyString());
    }

    @Test
    void registrationNeverHashesAPassword() {
        org.mockito.Mockito.clearInvocations(encoder);
        service.register(CAROL, "10.0.0.1");
        service.register(TestFixtures.ALICE, "10.0.0.1");
        service.register(TestFixtures.DISABLED, "10.0.0.1");
        verifyNoInteractions(encoder);
    }

    @Test
    void checkLinkIsReadOnly() {
        String raw = fx.tokenGenerator.generate();
        stored(raw, CAROL);
        assertThat(service.checkLink(raw)).isEqualTo(TokenStatus.USABLE);
        verify(tokens, never()).markUsedIfUsable(anyLong(), any());
    }

    @Test
    void completeCreatesActiveAccountAndUsesLink() {
        String raw = fx.tokenGenerator.generate();
        stored(raw, CAROL);
        when(tokens.markUsedIfUsable(eq(9L), any())).thenReturn(1);

        assertThat(service.completeRegistration(raw, "carol-password", "carol-password"))
                .isInstanceOf(RegistrationOutcome.Completed.class);
        ArgumentCaptor<UserAccount> account = ArgumentCaptor.forClass(UserAccount.class);
        verify(fx.accounts).save(account.capture());
        assertThat(account.getValue().getEmail()).isEqualTo(CAROL);
        assertThat(account.getValue().isActive()).isTrue();
        assertThat(encoder.matches("carol-password", account.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void ruleFailureCreatesNothingAndKeepsLink() {
        String raw = fx.tokenGenerator.generate();
        stored(raw, CAROL);

        assertThat(service.completeRegistration(raw, "short", "short"))
                .isEqualTo(new RegistrationOutcome.Rejected(List.of(PasswordViolation.TOO_SHORT)));
        verify(tokens, never()).markUsedIfUsable(anyLong(), any());
        verify(fx.accounts, never()).save(any());
    }

    @Test
    void invalidLinksAreRejected() {
        String raw = fx.tokenGenerator.generate();
        RegistrationToken token = stored(raw, CAROL);

        fx.clock.setInstant(TestFixtures.START.plus(Duration.ofHours(24)));
        assertThat(service.completeRegistration(raw, "carol-password", "carol-password"))
                .isEqualTo(RegistrationOutcome.INVALID);
        fx.clock.setInstant(TestFixtures.START);
        ServiceFixtures.set(token, "usedAt", TestFixtures.START);
        assertThat(service.checkLink(raw)).isEqualTo(TokenStatus.INVALID_OR_EXPIRED);
        ServiceFixtures.set(token, "usedAt", null);
        ServiceFixtures.set(token, "invalidatedAt", TestFixtures.START);
        assertThat(service.checkLink(raw)).isEqualTo(TokenStatus.INVALID_OR_EXPIRED);
        assertThat(service.checkLink(fx.tokenGenerator.generate())).isEqualTo(TokenStatus.INVALID_OR_EXPIRED);
        assertThat(service.checkLink("garbage")).isEqualTo(TokenStatus.INVALID_OR_EXPIRED);
        verify(fx.accounts, never()).save(any());
    }

    @Test
    void racingCompletionHittingUniqueEmailIsInvalid() {
        String raw = fx.tokenGenerator.generate();
        stored(raw, CAROL);
        when(tokens.markUsedIfUsable(eq(9L), any())).thenReturn(1);
        when(fx.accounts.save(any())).thenThrow(new DataIntegrityViolationException("uq_user_account_email"));

        assertThat(service.completeRegistration(raw, "carol-password", "carol-password"))
                .isEqualTo(RegistrationOutcome.INVALID);
    }
}

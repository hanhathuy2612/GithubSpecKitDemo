package com.example.resetpassword.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.resetpassword.service.CredentialService;
import com.example.resetpassword.support.ServiceFixtures;
import com.example.resetpassword.support.TestFixtures;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CredentialServiceTest {

    private ServiceFixtures fx;
    private CredentialService credentials;

    @BeforeEach
    void setUp() {
        fx = new ServiceFixtures();
        fx.account(1L, TestFixtures.ALICE, true);
        fx.account(3L, TestFixtures.DISABLED, false);
        when(fx.accounts.findByEmail(TestFixtures.NOBODY)).thenReturn(Optional.empty());
        credentials = new CredentialService(fx.accounts, fx.passwordEncoder);
    }

    @Test
    void correctPasswordIsAccepted() {
        assertThat(credentials.verifyCredentials(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD)).isTrue();
    }

    @Test
    void emailIsNormalized() {
        assertThat(credentials.verifyCredentials(" Alice@Example.com ", TestFixtures.SEED_PASSWORD)).isTrue();
    }

    @Test
    void wrongPasswordUnknownEmailAndDisabledAccountAreRejected() {
        assertThat(credentials.verifyCredentials(TestFixtures.ALICE, "wrong-password")).isFalse();
        assertThat(credentials.verifyCredentials(TestFixtures.NOBODY, TestFixtures.SEED_PASSWORD)).isFalse();
        assertThat(credentials.verifyCredentials(TestFixtures.DISABLED, TestFixtures.SEED_PASSWORD)).isFalse();
    }

    @Test
    void overlongPasswordIsRejectedWithoutError() {
        assertThat(credentials.verifyCredentials(TestFixtures.ALICE, "x".repeat(100))).isFalse();
        assertThat(credentials.verifyCredentials(TestFixtures.ALICE, null)).isFalse();
    }
}

package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.resetpassword.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Phase 2 checkpoint: context loads, Flyway applied V1/V2, MockMvc resolves. */
class ApplicationContextSmokeTest extends IntegrationTestBase {

    @Test
    void flywaySeededAccounts() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_account", Integer.class);
        assertThat(count).isEqualTo(3);
        assertThat(mockMvc).isNotNull();
    }
}

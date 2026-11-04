package com.example.resetpassword.unit;

import static com.example.resetpassword.service.PasswordViolation.MISMATCH;
import static com.example.resetpassword.service.PasswordViolation.SAME_AS_CURRENT;
import static com.example.resetpassword.service.PasswordViolation.TOO_LONG;
import static com.example.resetpassword.service.PasswordViolation.TOO_SHORT;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.resetpassword.service.PasswordPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordPolicyTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final PasswordPolicy policy = new PasswordPolicy(encoder);
    private final String currentHash = encoder.encode("demo-password-1");

    private java.util.List<com.example.resetpassword.service.PasswordViolation> check(String pw) {
        return policy.check(pw, pw, currentHash);
    }

    @Test
    void sevenCharactersIsTooShort() {
        assertThat(check("a".repeat(7))).containsExactly(TOO_SHORT);
    }

    @Test
    void eightAndSixtyFourCharactersAreAccepted() {
        assertThat(check("a".repeat(8))).isEmpty();
        assertThat(check("a".repeat(64))).isEmpty();
    }

    @Test
    void sixtyFiveCharactersIsTooLong() {
        assertThat(check("a".repeat(65))).containsExactly(TOO_LONG);
    }

    @Test
    void sixtyFourCodePointsOverSeventyTwoBytesIsTooLong() {
        String vietnamese = "ệ".repeat(64); // 3 bytes each in UTF-8 = 192 bytes
        assertThat(vietnamese.codePointCount(0, vietnamese.length())).isEqualTo(64);
        assertThat(check(vietnamese)).containsExactly(TOO_LONG);
    }

    @Test
    void codePointsNotCharsAreCounted() {
        String emoji = "😀".repeat(8); // 8 code points, 16 UTF-16 chars, 32 bytes
        assertThat(check(emoji)).isEmpty();
    }

    @Test
    void noCharacterClassRequirement() {
        assertThat(check("aaaaaaaa")).isEmpty();
    }

    @Test
    void confirmationMustMatch() {
        assertThat(policy.check("new-password-1", "new-password-2", currentHash)).containsExactly(MISMATCH);
    }

    @Test
    void mustDifferFromCurrentPassword() {
        assertThat(check("demo-password-1")).containsExactly(SAME_AS_CURRENT);
    }

    @Test
    void nullPasswordIsTooShort() {
        assertThat(policy.check(null, null, currentHash)).containsExactly(TOO_SHORT);
    }
}

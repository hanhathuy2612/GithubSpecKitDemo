package com.example.resetpassword.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.resetpassword.security.TokenGenerator;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenGeneratorTest {

    private final TokenGenerator generator = new TokenGenerator();

    @Test
    void generatesBase64UrlTokensOf43Chars() {
        String token = generator.generate();
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void tokensAreUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            tokens.add(generator.generate());
        }
        assertThat(tokens).hasSize(1_000);
    }

    @Test
    void hashIs64LowercaseHexAndDeterministic() {
        String token = generator.generate();
        assertThat(generator.hash(token)).matches("[0-9a-f]{64}").isEqualTo(generator.hash(token));
        assertThat(generator.hash(token)).isNotEqualTo(generator.hash(generator.generate()));
    }

    @Test
    void wellFormedCheck() {
        assertThat(generator.isWellFormed(generator.generate())).isTrue();
        assertThat(generator.isWellFormed(null)).isFalse();
        assertThat(generator.isWellFormed("short")).isFalse();
        assertThat(generator.isWellFormed("a".repeat(44))).isFalse();
        assertThat(generator.isWellFormed("+".repeat(43))).isFalse();
    }
}

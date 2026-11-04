package com.example.resetpassword.security;

import com.example.resetpassword.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Builds the 15-minute ES256 access token (plan.md › JWT). No email or other PII in claims. */
@Component
public class AccessTokenIssuer {

    public static final Duration ACCESS_TTL = Duration.ofMinutes(15);

    public record AccessToken(String value, long expiresIn) {
    }

    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final String keyId;

    public AccessTokenIssuer(JwtEncoder encoder, Clock clock, AppProperties properties) {
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = properties.jwt().issuer();
        this.keyId = properties.jwt().keyId();
    }

    public AccessToken issue(long accountId, String sessionId, int sessionVersion) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(Long.toString(accountId))
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TTL))
                .id(UUID.randomUUID().toString())
                .claim("sid", sessionId)
                .claim("sv", sessionVersion)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keyId).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, ACCESS_TTL.toSeconds());
    }
}

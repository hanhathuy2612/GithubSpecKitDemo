package com.example.resetpassword.security;

import com.example.resetpassword.config.AppProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** ES256 signing key (002 research R2) and the JWT encoder/decoder built on it. */
@Configuration(proxyBeanMethods = false)
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    ECKey jwtSigningKey(AppProperties properties, Environment environment) throws Exception {
        AppProperties.Jwt jwt = properties.jwt();
        if (jwt.privateKey() == null || jwt.privateKey().isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("APP_JWT_PRIVATE_KEY and APP_JWT_PUBLIC_KEY are required in prod");
            }
            log.warn("auth.jwt_key_generated kid={} (dev/test only; access tokens do not survive a restart)",
                    jwt.keyId());
            return new ECKeyGenerator(Curve.P_256).keyID(jwt.keyId()).generate();
        }
        KeyFactory factory = KeyFactory.getInstance("EC");
        ECPrivateKey privateKey = (ECPrivateKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(pemBody(jwt.privateKey())));
        ECPublicKey publicKey = (ECPublicKey) factory.generatePublic(
                new X509EncodedKeySpec(pemBody(jwt.publicKey())));
        return new ECKey.Builder(Curve.P_256, publicKey).privateKey(privateKey).keyID(jwt.keyId()).build();
    }

    @Bean
    JwtEncoder jwtEncoder(ECKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtSigningKey)));
    }

    /**
     * Validators are set explicitly with the injected Clock (002 research R1): never JwtValidators.createDefault…,
     * whose timestamp check uses the system clock.
     */
    @Bean
    JwtDecoder jwtDecoder(ECKey jwtSigningKey, AppProperties properties, Clock clock,
            SessionVersionValidator sessionVersionValidator) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSource(new ImmutableJWKSet<>(new JWKSet(jwtSigningKey.toPublicJWK())))
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        JwtTimestampValidator timestamp = new JwtTimestampValidator(Duration.ZERO);
        timestamp.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.jwt().issuer()), timestamp, sessionVersionValidator));
        return decoder;
    }

    private static byte[] pemBody(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("APP_JWT_PUBLIC_KEY must be set together with APP_JWT_PRIVATE_KEY");
        }
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}

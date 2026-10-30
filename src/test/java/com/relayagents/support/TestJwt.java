package com.relayagents.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Mints tokens signed with a key generated per JVM, and swaps the app's issuer-backed decoder for one that
 * trusts that key, so tests exercise the real filter chain without an identity provider.
 */
public final class TestJwt {

    private static final KeyPair KEYS = generate();

    private TestJwt() {
    }

    public static String token(String tenant, String subject, String... scopes) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer("http://test-issuer/relay")
            .subject(subject)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(600)))
            .claim("scope", String.join(" ", scopes));
        if (tenant != null) {
            claims.claim("tenant", tenant);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        try {
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Decoder {

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        }
    }
}

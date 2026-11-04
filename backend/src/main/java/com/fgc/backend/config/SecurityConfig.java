package com.fgc.backend.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig {

    @Bean
    SecretKey jwtKey(FgcProperties props) {
        String s = props.jwtSecret();
        if (s == null || s.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("FGC_JWT_SECRET must be set and at least 32 bytes");
        }
        return new SecretKeySpec(s.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey, java.time.Clock clock) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        // 발급과 같은 Clock 으로 만료를 검증하고, 발급자도 확인한다
        JwtTimestampValidator ts = new JwtTimestampValidator(java.time.Duration.ofSeconds(30));
        ts.setClock(clock);
        d.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(ts, new JwtIssuerValidator("fgc")));
        return d;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/ns/**").permitAll()                 // Nightscout 업로드: 자체 api-secret 검증
                    .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                    .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> {}));
        return http.build();
    }
}

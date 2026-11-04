package com.fgc.backend.common;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

public final class Auth {
    private Auth() {}

    public static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

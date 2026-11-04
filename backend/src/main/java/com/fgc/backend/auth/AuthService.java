package com.fgc.backend.auth;

import com.fgc.backend.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.fgc.backend.common.Db.ts;

@Service
public class AuthService {
    private static final int MAX_FAILURES = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(5);

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public AuthService(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwords, TokenService tokens, Clock clock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.clock = clock;
    }

    public TokenService.Tokens signup(String email, String password, String displayName) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO users (id, email, password_hash, display_name, created_at) VALUES (:id, :e, :p, :n, :c)",
                    new MapSqlParameterSource().addValue("id", id).addValue("e", email.trim().toLowerCase())
                            .addValue("p", passwords.encode(password)).addValue("n", displayName.trim())
                            .addValue("c", ts(clock.instant())));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Email already registered");
        }
        return tokens.issue(id);
    }

    public TokenService.Tokens login(String email, String password) {
        String key = email.trim().toLowerCase();
        Instant now = clock.instant();
        Deque<Instant> f = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (f) {
            while (!f.isEmpty() && f.peekFirst().isBefore(now.minus(FAILURE_WINDOW))) f.pollFirst();
            if (f.size() >= MAX_FAILURES) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", "Too many failed logins. Try again later");
            }
        }
        List<Object[]> rows = jdbc.query("SELECT id, password_hash FROM users WHERE lower(email) = :e AND deleted_at IS NULL",
                new MapSqlParameterSource("e", key), (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)});
        if (rows.isEmpty() || !passwords.matches(password, (String) rows.get(0)[1])) {
            synchronized (f) { f.addLast(now); }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Wrong email or password");
        }
        failures.remove(key);
        return tokens.issue((UUID) rows.get(0)[0]);
    }

    public boolean passwordMatches(UUID userId, String password) {
        List<String> h = jdbc.queryForList("SELECT password_hash FROM users WHERE id = :id AND deleted_at IS NULL",
                new MapSqlParameterSource("id", userId), String.class);
        return !h.isEmpty() && passwords.matches(password, h.get(0));
    }
}

package com.fgc.backend.auth;

import com.fgc.backend.common.ApiException;
import com.fgc.backend.common.Secrets;
import com.fgc.backend.config.FgcProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.fgc.backend.common.Db.ts;

/**
 * Access JWT(짧은 수명) + Refresh 토큰(회전). Refresh 토큰은 해시만 저장.
 * 이미 회전된(폐기된) Refresh 토큰이 다시 쓰이면 탈취로 보고 그 사용자의 모든 Refresh 토큰을 폐기한다.
 */
@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final NamedParameterJdbcTemplate jdbc;
    private final FgcProperties props;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, NamedParameterJdbcTemplate jdbc, FgcProperties props, Clock clock) {
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    public record Tokens(String accessToken, String refreshToken, String tokenType, long expiresIn) {}

    public Tokens issue(UUID userId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("fgc").subject(userId.toString()).issuedAt(now).expiresAt(now.plus(props.accessTokenTtl())).build();
        String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        String refresh = Secrets.randomToken(32);
        jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at) VALUES (:id, :u, :h, :e, :c)",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("u", userId)
                        .addValue("h", Secrets.sha256Hex(refresh)).addValue("e", ts(now.plus(props.refreshTokenTtl())))
                        .addValue("c", ts(now)));
        return new Tokens(access, refresh, "Bearer", props.accessTokenTtl().toSeconds());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Tokens rotate(String refreshToken) {
        Instant now = clock.instant();
        List<Object[]> rows = jdbc.query("""
                SELECT rt.id, rt.user_id, rt.expires_at, rt.revoked_at, u.deleted_at
                FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id WHERE rt.token_hash = :h FOR UPDATE OF rt""",
                new MapSqlParameterSource("h", Secrets.sha256Hex(refreshToken)),
                (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4), rs.getTimestamp(5)});
        if (rows.isEmpty()) throw invalid();
        Object[] r = rows.get(0);
        UUID userId = (UUID) r[1];
        if (r[3] != null) { // 재사용 감지
            revokeAll(userId);
            throw invalid();
        }
        if (r[4] != null || !now.isBefore((Instant) r[2])) throw invalid();
        jdbc.update("UPDATE refresh_tokens SET revoked_at = :t WHERE id = :id",
                new MapSqlParameterSource().addValue("t", ts(now)).addValue("id", r[0]));
        return issue(userId);
    }

    public void revoke(String refreshToken) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = :t WHERE token_hash = :h AND revoked_at IS NULL",
                new MapSqlParameterSource().addValue("t", ts(clock.instant())).addValue("h", Secrets.sha256Hex(refreshToken)));
    }

    public void revokeAll(UUID userId) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = :t WHERE user_id = :u AND revoked_at IS NULL",
                new MapSqlParameterSource().addValue("t", ts(clock.instant())).addValue("u", userId));
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token", "Refresh token invalid or expired");
    }
}

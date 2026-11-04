package com.fgc.backend.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

public final class Db {
    private Db() {}

    public static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    public static UUID uuid(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, UUID.class);
    }

    public static Integer integer(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}

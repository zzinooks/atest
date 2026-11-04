package com.fgc.backend.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class Secrets {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {}

    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** 사람이 입력하기 쉬운 초대 코드 (혼동 문자 제외, 10자 ≈ 50bit) */
    public static String inviteCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return sb.toString();
    }

    public static String sha256Hex(String s) {
        return HexFormat.of().formatHex(digest("SHA-256", s));
    }

    public static String sha1Hex(String s) {
        return HexFormat.of().formatHex(digest("SHA-1", s));
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digest(String alg, String s) {
        try {
            return MessageDigest.getInstance(alg).digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

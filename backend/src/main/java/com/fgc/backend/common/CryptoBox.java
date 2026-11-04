package com.fgc.backend.common;

import com.fgc.backend.config.FgcProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** 데이터 소스 자격증명 암호화 (AES-256-GCM, 키는 FGC_ENCRYPTION_KEY = base64 32바이트). */
@Component
public class CryptoBox {
    private static final int IV_LEN = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoBox(FgcProperties props) {
        if (props.encryptionKey() == null || props.encryptionKey().isBlank()) {
            throw new IllegalStateException("FGC_ENCRYPTION_KEY must be set (base64 of 32 bytes)");
        }
        byte[] k = Base64.getDecoder().decode(props.encryptionKey());
        if (k.length != 32) throw new IllegalStateException("FGC_ENCRYPTION_KEY must decode to 32 bytes");
        this.key = new SecretKeySpec(k, "AES");
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(IV_LEN + ct.length).put(iv).put(ct).array();
        } catch (Exception e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, IV_LEN));
            return new String(c.doFinal(data, IV_LEN, data.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }
}

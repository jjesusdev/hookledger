package com.jjesusdev.hookledger.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

public final class Hmac {

    private static final String ALGORITHM = "HmacSHA256";

    private Hmac() {
    }

    /**
     * Signature over {@code timestamp + "." + body}, the wire format used by webhook providers.
     */
    public static byte[] sign(String secret, String timestamp, byte[] body) {
        Mac mac = newMac(secret);
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        mac.update(body);
        return mac.doFinal();
    }

    public static String signHex(String secret, String timestamp, byte[] body) {
        return HexFormat.of().formatHex(sign(secret, timestamp, body));
    }

    /**
     * Raw HMAC over arbitrary bytes. Useful for known-answer tests and providers that sign the body only.
     */
    public static byte[] signRaw(String secret, byte[] data) {
        Mac mac = newMac(secret);
        mac.update(data);
        return mac.doFinal();
    }

    private static Mac newMac(String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}

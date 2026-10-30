package com.relayagents.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacSha256 {

    private HmacSha256() {
    }

    public static String hex(String key, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by the JDK spec", e);
        }
    }

    /**
     * Constant-time comparison, so a caller cannot learn the signature one byte at a time.
     */
    public static boolean matches(String key, byte[] body, String presentedHex) {
        if (presentedHex == null) {
            return false;
        }
        byte[] expected = hex(key, body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, presentedHex.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }
}

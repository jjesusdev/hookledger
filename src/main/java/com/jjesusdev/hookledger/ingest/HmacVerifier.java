package com.jjesusdev.hookledger.ingest;

import com.jjesusdev.hookledger.config.HookledgerProperties;
import com.jjesusdev.hookledger.crypto.Hmac;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

@Component
public class HmacVerifier {

    private final HookledgerProperties properties;
    private final Clock clock;

    public HmacVerifier(HookledgerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void verify(String source, String timestamp, String signature, byte[] body) {
        HookledgerProperties.Source sourceConfig = properties.sources().get(source);
        if (sourceConfig == null || sourceConfig.secret().isBlank()) {
            throw new WebhookRejectedException(401, "UNKNOWN_SOURCE", "Unknown webhook source");
        }
        verifyWithSecret(sourceConfig.secret(), timestamp, signature, body);
    }

    /**
     * Used by the demo receiver and any endpoint that has its own secret rather than a configured
     * inbound source.
     */
    public void verifyWithSecret(String secret, String timestamp, String signature, byte[] body) {
        if (secret == null || secret.isBlank()) {
            throw new WebhookRejectedException(401, "UNKNOWN_SOURCE", "Signature secret is not configured");
        }

        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new WebhookRejectedException(401, "INVALID_TIMESTAMP", "Timestamp must be epoch seconds");
        }

        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestampSeconds) > properties.security().maxClockSkewSeconds()) {
            throw new WebhookRejectedException(401, "TIMESTAMP_OUT_OF_WINDOW",
                    "Timestamp is outside the allowed clock skew");
        }

        byte[] expected = Hmac.sign(secret, timestamp, body);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException e) {
            throw new WebhookRejectedException(401, "INVALID_SIGNATURE", "Signature must be hex-encoded");
        }

        if (!MessageDigest.isEqual(expected, provided)) {
            throw new WebhookRejectedException(401, "INVALID_SIGNATURE", "Signature does not match");
        }
    }
}

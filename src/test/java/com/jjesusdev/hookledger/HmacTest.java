package com.jjesusdev.hookledger;

import com.jjesusdev.hookledger.config.HookledgerProperties;
import com.jjesusdev.hookledger.crypto.Hmac;
import com.jjesusdev.hookledger.ingest.HmacVerifier;
import com.jjesusdev.hookledger.ingest.WebhookRejectedException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HmacTest {

    private static final String SECRET = "test-secret";
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final long TIMESTAMP = NOW.getEpochSecond();
    private static final byte[] BODY = "{\"type\":\"demo.ping\"}".getBytes(StandardCharsets.UTF_8);

    private final HmacVerifier verifier = new HmacVerifier(properties(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsValidSignature() {
        String signature = Hmac.signHex(SECRET, Long.toString(TIMESTAMP), BODY);

        assertThatCode(() -> verifier.verify("demo", Long.toString(TIMESTAMP), signature, BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void matchesKnownHmacVector() {
        byte[] digest = Hmac.signRaw("key", "The quick brown fox jumps over the lazy dog"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(HexFormat.of().formatHex(digest))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void rejectsTamperedBody() {
        String signature = Hmac.signHex(SECRET, Long.toString(TIMESTAMP), BODY);

        WebhookRejectedException error = assertThrows(WebhookRejectedException.class,
                () -> verifier.verify("demo", Long.toString(TIMESTAMP), signature,
                        "{\"type\":\"other\"}".getBytes(StandardCharsets.UTF_8)));

        assertThat(error.status()).isEqualTo(401);
        assertThat(error.code()).isEqualTo("INVALID_SIGNATURE");
    }

    @Test
    void rejectsExpiredTimestamp() {
        long oldTimestamp = TIMESTAMP - 301;
        String signature = Hmac.signHex(SECRET, Long.toString(oldTimestamp), BODY);

        WebhookRejectedException error = assertThrows(WebhookRejectedException.class,
                () -> verifier.verify("demo", Long.toString(oldTimestamp), signature, BODY));

        assertThat(error.status()).isEqualTo(401);
        assertThat(error.code()).isEqualTo("TIMESTAMP_OUT_OF_WINDOW");
    }

    @Test
    void rejectsUnknownSource() {
        WebhookRejectedException error = assertThrows(WebhookRejectedException.class,
                () -> verifier.verify("unknown", Long.toString(TIMESTAMP), "00", BODY));

        assertThat(error.status()).isEqualTo(401);
        assertThat(error.code()).isEqualTo("UNKNOWN_SOURCE");
    }

    @Test
    void rejectsMalformedSignature() {
        WebhookRejectedException error = assertThrows(WebhookRejectedException.class,
                () -> verifier.verify("demo", Long.toString(TIMESTAMP), "not-hex", BODY));

        assertThat(error.status()).isEqualTo(401);
        assertThat(error.code()).isEqualTo("INVALID_SIGNATURE");
    }

    private static HookledgerProperties properties() {
        return new HookledgerProperties(
                new HookledgerProperties.Security(300, 262_144),
                Map.of("demo", new HookledgerProperties.Source(SECRET)),
                new HookledgerProperties.Outbox(false, 500, 50),
                "log",
                new HookledgerProperties.Demo(true)
        );
    }
}

package com.jjesusdev.hookledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "hookledger")
public record HookledgerProperties(
        Security security,
        Map<String, Source> sources,
        Outbox outbox,
        String publisher,
        Demo demo
) {

    public HookledgerProperties {
        if (security == null) {
            security = new Security(300, 262_144);
        }
        if (sources == null) {
            sources = Map.of();
        }
        if (outbox == null) {
            outbox = new Outbox(true, 500, 50);
        }
        if (publisher == null || publisher.isBlank()) {
            publisher = "log";
        }
        if (demo == null) {
            demo = new Demo(false);
        }
    }

    public record Security(long maxClockSkewSeconds, int maxBodyBytes) {
        public Security {
            if (maxClockSkewSeconds <= 0) {
                maxClockSkewSeconds = 300;
            }
            if (maxBodyBytes <= 0) {
                maxBodyBytes = 262_144;
            }
        }
    }

    public record Source(String secret) {
        public Source {
            if (secret == null) {
                secret = "";
            }
        }
    }

    public record Outbox(boolean enabled, long pollIntervalMs, int batchSize) {
        public Outbox {
            if (pollIntervalMs <= 0) {
                pollIntervalMs = 500;
            }
            if (batchSize <= 0) {
                batchSize = 50;
            }
        }
    }

    public record Demo(boolean enabled) {
    }
}

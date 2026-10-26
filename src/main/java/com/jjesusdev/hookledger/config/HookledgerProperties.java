package com.jjesusdev.hookledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "hookledger")
public record HookledgerProperties(
        Security security,
        Map<String, Source> sources,
        Outbox outbox,
        String publisher,
        Demo demo,
        Sqs sqs,
        Worker worker,
        Map<String, Target> targets,
        Admin admin
) {

    public HookledgerProperties {
        if (security == null) {
            security = new Security(300, 200_000);
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
        if (sqs == null) {
            sqs = new Sqs("", "", "us-east-1", "");
        }
        if (worker == null) {
            worker = new Worker(false, 1000, 5, 10, 3, 30, 2, 60);
        }
        if (targets == null) {
            targets = Map.of();
        }
        if (admin == null) {
            admin = new Admin("");
        }
    }

    /**
     * Binding uses the canonical constructor. Keep this record to a single constructor: Spring Boot
     * cannot choose between multiple constructors for configuration properties.
     */

    public record Security(long maxClockSkewSeconds, int maxBodyBytes) {
        public Security {
            if (maxClockSkewSeconds <= 0) {
                maxClockSkewSeconds = 300;
            }
            if (maxBodyBytes <= 0) {
                maxBodyBytes = 200_000;
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

    public record Sqs(String queueUrl, String dlqUrl, String region, String endpoint) {
        public Sqs {
            if (queueUrl == null) {
                queueUrl = "";
            }
            if (dlqUrl == null) {
                dlqUrl = "";
            }
            if (region == null || region.isBlank()) {
                region = "us-east-1";
            }
            if (endpoint == null) {
                endpoint = "";
            }
        }
    }

    public record Worker(boolean enabled,
                         long pollIntervalMs,
                         int waitTimeSeconds,
                         int maxMessages,
                         int maxReceives,
                         int visibilityTimeoutSeconds,
                         int backoffBaseSeconds,
                         int backoffMaxSeconds) {
        public Worker {
            if (pollIntervalMs <= 0) {
                pollIntervalMs = 1000;
            }
            waitTimeSeconds = Math.clamp(waitTimeSeconds, 0, 20);
            maxMessages = Math.clamp(maxMessages, 1, 10);
            if (maxReceives <= 0) {
                maxReceives = 3;
            }
            if (visibilityTimeoutSeconds <= 0) {
                visibilityTimeoutSeconds = 30;
            }
            if (backoffBaseSeconds <= 0) {
                backoffBaseSeconds = 2;
            }
            if (backoffMaxSeconds <= 0) {
                backoffMaxSeconds = 60;
            }
        }
    }

    public record Target(String url, String secret) {
        public Target {
            if (url == null) {
                url = "";
            }
            if (secret == null) {
                secret = "";
            }
        }
    }

    public record Admin(String token) {
        public Admin {
            if (token == null) {
                token = "";
            }
        }
    }
}

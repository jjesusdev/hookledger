package com.jjesusdev.hookledger.ingest;

import java.util.Map;

public class WebhookRejectedException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, Object> properties;

    public WebhookRejectedException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public WebhookRejectedException(int status, String code, String message, Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}

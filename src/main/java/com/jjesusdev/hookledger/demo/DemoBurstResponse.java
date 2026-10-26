package com.jjesusdev.hookledger.demo;

public record DemoBurstResponse(
        int requested,
        int accepted,
        int duplicates,
        int conflicts,
        long durationMs
) {
}

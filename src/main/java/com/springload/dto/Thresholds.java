package com.springload.dto;

public record Thresholds(
    int p95LatencyMs,
    int p99LatencyMs,
    double maxErrorPercentage
) {}
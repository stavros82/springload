package com.springload.dto;

public record ScenarioSummary(
    String scenarioName,
    String method,
    String path,
    long totalRequests,
    long successfulRequests,
    long failedRequests,
    double p50LatencyMs,
    double p95LatencyMs,
    double p99LatencyMs,
    double errorRatePercentage
) {}
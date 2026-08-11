package com.springload.dto;

import java.time.LocalDateTime;
import java.util.List;

public record TestReport(
    String reportId,
    String testName,
    LocalDateTime startTime,
    LocalDateTime endTime,
    long durationSeconds,
    int concurrency,
    long totalRequests,
    long totalSuccesses,
    long totalErrors,
    double requestsPerSecond,
    double overallP95LatencyMs,
    double overallP99LatencyMs,
    boolean passedThresholds,
    List<ScenarioSummary> scenarios
) {}
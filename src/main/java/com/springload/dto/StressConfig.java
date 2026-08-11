package com.springload.dto;

import java.util.List;

public record StressConfig(
    String name,
    String targetBaseUrl,
    ExecutionSettings execution,
    List<ScenarioConfig> scenarios,
    Thresholds thresholds
) {}
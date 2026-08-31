package com.springload.dto;

import java.util.Map;

public record ScenarioConfig(
    String name,
    String method,
    String path,
    int weight,
    Map<String, String> headers,
    Map<String, String> queryParams,
    String body,
    boolean enabled,
    Map<String, String> extractedVariables
) {}
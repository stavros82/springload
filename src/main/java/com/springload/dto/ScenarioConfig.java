package com.springload.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioConfig(
    String name,
    String method,
    String path,
    int weight,
    Map<String, String> headers,
    Map<String, String> queryParams,
    String body,
    boolean enabled,
    Boolean active,
    Map<String, String> extractedVariables
) {
    public boolean isActive() {
        return active != null ? active : enabled;
    }
}

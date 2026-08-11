package com.springload.dto;

public record ExecutionSettings(
    int concurrency,
    int durationSeconds,
    int rampUpSeconds
) {}
package com.springload.service;

import com.springload.dto.StressConfig;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface LoadExecutionService {
    SseEmitter executeTest(StressConfig config);
}
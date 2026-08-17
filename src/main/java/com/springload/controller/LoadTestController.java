package com.springload.controller;

import com.springload.dto.StressConfig;
import com.springload.service.LoadExecutionService;
import com.springload.service.VirtualThreadsLoadExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/test")
public class LoadTestController {

    private static final Logger log = LoggerFactory.getLogger(LoadTestController.class);

    private final LoadExecutionService virtualThreadService;
    private final LoadExecutionService reactiveService;

    public LoadTestController(
            @Qualifier("virtualThreadEngine") LoadExecutionService virtualThreadService,
            @Qualifier("reactiveEngine") LoadExecutionService reactiveService) {
        this.virtualThreadService = virtualThreadService;
        this.reactiveService = reactiveService;
    }

    @PostMapping(value = "/run", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runTest(@RequestBody StressConfig config,@RequestParam(name = "engine", defaultValue = "virtual") String engine) {
        log.info("Received HTTP POST request to /api/v1/test/run for test configuration: '{}'",
                config != null ? config.name() : "NULL");

        if (config == null || config.scenarios() == null || config.scenarios().isEmpty()) {
            log.error("Rejected load test submission: StressConfig contains no valid scenarios.");
            throw new IllegalArgumentException("StressConfig must contain at least one valid scenario.");
        }

        log.info("Config Validation Passed: Target Base URL='{}', Scenarios Count={}, Concurrency={}, Duration={}s",
                config.targetBaseUrl(), config.scenarios().size(), config.execution().concurrency(), config.execution().durationSeconds());

        if ("reactive".equalsIgnoreCase(engine)) {
            return reactiveService.executeTest(config);
        } else {
            return virtualThreadService.executeTest(config);
        }
    }
}
package com.springload.service;

import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.TestReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LoadExecutionService {

    private static final Logger log = LoggerFactory.getLogger(LoadExecutionService.class);
    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("\\{([^}]+)\\}");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ReportService reportService;

    public LoadExecutionService(ReportService reportService) {
        this.reportService = reportService;
    }

    public SseEmitter executeTest(StressConfig config) {
        log.info("Starting load test: '{}' targeting Base URL: '{}' with concurrency: {} for duration: {}s",
                config.name(), config.targetBaseUrl(), config.execution().concurrency(), config.execution().durationSeconds());

        SseEmitter emitter = new SseEmitter(0L); // Infinite timeout for SSE stream
        AtomicLong requestCounter = new AtomicLong();
        AtomicLong errorCounter = new AtomicLong();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        LocalDateTime startTime = LocalDateTime.now();
        long startMillis = System.currentTimeMillis();
        long endTime = startMillis + (config.execution().durationSeconds() * 1000L);

        // 1. Run Virtual Threads asynchronously
        Thread.ofVirtual().name("load-generator-", 0).start(() -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                config.scenarios().stream()
                    .filter(ScenarioConfig::enabled)
                    .forEach(scenario -> {
                        log.info("Initializing Virtual Threads for scenario: [{}] {}", scenario.method(), scenario.path());
                        
                        for (int i = 0; i < config.execution().concurrency(); i++) {
                            executor.submit(() -> {
                                while (System.currentTimeMillis() < endTime) {
                                    // Dynamically replace OpenAPI path template variables
                                    String resolvedPath = resolvePathVariables(scenario.path());
                                    String fullUrl = config.targetBaseUrl() + resolvedPath;

                                    try {
                                        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                                .uri(URI.create(fullUrl))
                                                .timeout(Duration.ofSeconds(5));

                                        if (scenario.headers() != null) {
                                            scenario.headers().forEach(builder::header);
                                        }

                                        String method = scenario.method().toUpperCase();
                                        HttpRequest.BodyPublisher bodyPublisher = (scenario.body() != null && !scenario.body().isBlank())
                                                ? HttpRequest.BodyPublishers.ofString(scenario.body())
                                                : HttpRequest.BodyPublishers.noBody();

                                        if ("POST".equals(method)) {
                                            builder.POST(bodyPublisher);
                                        } else if ("PUT".equals(method)) {
                                            builder.PUT(bodyPublisher);
                                        } else if ("DELETE".equals(method)) {
                                            builder.DELETE();
                                        } else {
                                            builder.GET();
                                        }

                                        long reqStart = System.currentTimeMillis();
                                        HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
                                        long reqDuration = System.currentTimeMillis() - reqStart;

                                        latencies.add(reqDuration);

                                        if (response.statusCode() >= 200 && response.statusCode() < 400) {
                                            long currentCount = requestCounter.incrementAndGet();
                                            log.debug("SUCCESS [{}] -> {} {} (Duration: {} ms, Total OK: {})", 
                                                    response.statusCode(), method, fullUrl, reqDuration, currentCount);
                                        } else {
                                            long currentErrors = errorCounter.incrementAndGet();
                                            log.warn("HTTP ERROR [{}] -> {} {} (Total Errors: {})", 
                                                    response.statusCode(), method, fullUrl, currentErrors);
                                        }
                                    } catch (Exception e) {
                                        long currentErrors = errorCounter.incrementAndGet();
                                        log.error("EXECUTION ERROR dispatching to {}: {} (Total Errors: {})", 
                                                fullUrl, e.getMessage(), currentErrors);
                                    }
                                }
                            });
                        }
                    });
            }
        });

        // 2. Stream execution metrics over SSE and complete test run
        ScheduledExecutorService metricScheduler = Executors.newSingleThreadScheduledExecutor();
        metricScheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            if (now >= endTime) {
                String reportId = UUID.randomUUID().toString();
                TestReport report = reportService.generateReport(reportId, config, startTime, latencies, requestCounter.get(), errorCounter.get());
                
                log.info("Load test '{}' completed. Final Successes: {}, Final Errors: {}, Report ID: {}",
                        config.name(), requestCounter.get(), errorCounter.get(), reportId);

                try {
                    emitter.send(SseEmitter.event()
                            .name("complete")
                            .data(Map.of(
                                "message", "Test execution complete",
                                "reportId", reportId,
                                "reportUrl", "/report.html?id=" + reportId
                            )));
                    emitter.complete();
                } catch (Exception e) {
                    log.error("Failed to send SSE complete event: {}", e.getMessage());
                }
                metricScheduler.shutdown();
                return;
            }

            try {
                long totalSuccess = requestCounter.get();
                long totalErrors = errorCounter.get();

                log.info("[PROGRESS] Time remaining: {}s | Successes: {} | Errors: {}",
                        (endTime - now) / 1000, totalSuccess, totalErrors);

                emitter.send(SseEmitter.event()
                        .name("metric")
                        .data(Map.of(
                            "timestamp", now,
                            "successfulRequests", totalSuccess,
                            "failedRequests", totalErrors
                        )));
            } catch (IOException e) {
                log.error("SSE Connection disconnected by client: {}", e.getMessage());
                metricScheduler.shutdown();
                emitter.completeWithError(e);
            }
        }, 1, 1, TimeUnit.SECONDS);

        return emitter;
    }

    /**
     * Dynamically replaces any {variableName} pattern in path templates
     * with context-aware mock values.
     */
    private String resolvePathVariables(String path) {
        if (path == null || !path.contains("{")) {
            return path;
        }

        Matcher matcher = PATH_VARIABLE_PATTERN.matcher(path);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1).toLowerCase();
            String mockValue;

            if (varName.contains("id") || varName.contains("amount") || varName.contains("quantity") || varName.contains("count")) {
                mockValue = String.valueOf(ThreadLocalRandom.current().nextInt(1, 1000));
            } else if (varName.contains("needed") || varName.contains("flag") || varName.contains("is") || varName.contains("active") || varName.contains("reorder")) {
                mockValue = String.valueOf(ThreadLocalRandom.current().nextBoolean());
            } else {
                mockValue = "val_" + UUID.randomUUID().toString().substring(0, 5);
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(mockValue));
        }
        matcher.appendTail(sb);
        
        String resolved = sb.toString();
        log.trace("Resolved path template '{}' -> '{}'", path, resolved);
        return resolved;
    }
}
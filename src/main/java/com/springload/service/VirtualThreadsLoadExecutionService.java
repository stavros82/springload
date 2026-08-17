package com.springload.service;

import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.TestReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Qualifier("virtualThreadEngine")
public class VirtualThreadsLoadExecutionService implements LoadExecutionService {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadsLoadExecutionService.class);
    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("\\{([^}]+)\\}");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ReportService reportService;

    public VirtualThreadsLoadExecutionService(ReportService reportService) {
        this.reportService = reportService;
    }

    @Override
    public SseEmitter executeTest(StressConfig config) {
        log.info("Starting load test: '{}' targeting Base URL: '{}' with concurrency: {} for duration: {}s",
                config.name(), config.targetBaseUrl(), config.execution().concurrency(), config.execution().durationSeconds());

        SseEmitter emitter = new SseEmitter(0L);
        AtomicLong requestCounter = new AtomicLong();
        AtomicLong errorCounter = new AtomicLong();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        LocalDateTime startTime = LocalDateTime.now();
        long startMillis = System.currentTimeMillis();
        long endTime = startMillis + (config.execution().durationSeconds() * 1000L);

        startVirtualThreadExecutor(config, endTime, requestCounter, errorCounter, latencies);
        scheduleMetricsEmitter(emitter, config, startTime, endTime, requestCounter, errorCounter, latencies);

        return emitter;
    }

    private void startVirtualThreadExecutor(StressConfig config, long endTime,
                                            AtomicLong requestCounter, AtomicLong errorCounter,
                                            List<Long> latencies) {
        Thread.ofVirtual().name("load-generator-", 0).start(() -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                config.scenarios().stream()
                        .filter(ScenarioConfig::enabled)
                        .forEach(scenario -> submitScenarioTasks(scenario, config, endTime, executor, requestCounter, errorCounter, latencies));
            }
        });
    }

    private void submitScenarioTasks(ScenarioConfig scenario, StressConfig config, long endTime,
                                     ExecutorService executor, AtomicLong requestCounter,
                                     AtomicLong errorCounter, List<Long> latencies) {
        log.info("Initializing Virtual Threads for scenario: [{}] {}", scenario.method(), scenario.path());

        for (int i = 0; i < config.execution().concurrency(); i++) {
            executor.submit(() -> executeScenarioLoop(scenario, config.targetBaseUrl(), endTime, requestCounter, errorCounter, latencies));
        }
    }

    private void executeScenarioLoop(ScenarioConfig scenario, String baseUrl, long endTime,
                                     AtomicLong requestCounter, AtomicLong errorCounter,
                                     List<Long> latencies) {
        while (System.currentTimeMillis() < endTime) {
            String resolvedPath = resolvePathVariables(scenario.path());
            String fullUrl = baseUrl + resolvedPath;
            String method = scenario.method().toUpperCase();

            try {
                HttpRequest request = buildHttpRequest(scenario, fullUrl, method);
                long reqStart = System.currentTimeMillis();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long reqDuration = System.currentTimeMillis() - reqStart;

                latencies.add(reqDuration);
                handleResponse(response, method, fullUrl, reqDuration, requestCounter, errorCounter);
            } catch (Exception e) {
                long currentErrors = errorCounter.incrementAndGet();
                log.error("EXECUTION ERROR dispatching to {} {}: {} (Total Errors: {})",
                        method, fullUrl, e.getMessage(), currentErrors, e);
            }
        }
    }

    private HttpRequest buildHttpRequest(ScenarioConfig scenario, String fullUrl, String method) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(5));

        if (scenario.headers() != null) {
            scenario.headers().forEach(builder::header);
        }

        HttpRequest.BodyPublisher bodyPublisher = (scenario.body() != null && !scenario.body().isBlank())
                ? HttpRequest.BodyPublishers.ofString(scenario.body())
                : HttpRequest.BodyPublishers.noBody();

        return switch (method) {
            case "POST" -> builder.POST(bodyPublisher).build();
            case "PUT" -> builder.PUT(bodyPublisher).build();
            case "DELETE" -> builder.DELETE().build();
            default -> builder.GET().build();
        };
    }

    private void handleResponse(HttpResponse<String> response, String method, String fullUrl,
                                long reqDuration, AtomicLong requestCounter, AtomicLong errorCounter) {
        int status = response.statusCode();
        if (status >= 200 && status < 400) {
            long currentCount = requestCounter.incrementAndGet();
            log.debug("SUCCESS [{}] -> {} {} (Duration: {} ms, Total OK: {})",
                    status, method, fullUrl, reqDuration, currentCount);
        } else {
            long currentErrors = errorCounter.incrementAndGet();
            String preview = extractResponseBodyPreview(response);
            log.warn("HTTP ERROR [{}] -> {} {} | Response Body: {} (Total Errors: {})",
                    status, method, fullUrl, preview, currentErrors);
        }
    }

    private String extractResponseBodyPreview(HttpResponse<String> response) {
        try {
            String body = response.body();
            if (body != null && body.length() > 200) {
                return body.substring(0, 200) + "... [truncated]";
            }
            return body;
        } catch (Exception ex) {
            return "Unable to read body: " + ex.getMessage();
        }
    }
    private void scheduleMetricsEmitter(SseEmitter emitter, StressConfig config,
                                        LocalDateTime startTime, long endTime,
                                        AtomicLong requestCounter, AtomicLong errorCounter,
                                        List<Long> latencies) {
        // Create a single-threaded scheduled executor without blocking the main thread via latch.await()
        ScheduledExecutorService metricScheduler = Executors.newSingleThreadScheduledExecutor();

        metricScheduler.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                if (now >= endTime) {
                    handleTestCompletion(emitter, config, startTime, endTime, requestCounter, errorCounter, latencies, metricScheduler);
                    return;
                }
                sendProgressMetric(emitter, now, endTime, requestCounter, errorCounter, metricScheduler);
            } catch (Exception e) {
                log.error("Error in metrics emitter task: {}", e.getMessage());
                metricScheduler.shutdown();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
    private void handleTestCompletion(SseEmitter emitter, StressConfig config,
                                      LocalDateTime startTime, long endTime,
                                      AtomicLong requestCounter, AtomicLong errorCounter,
                                      List<Long> latencies, ScheduledExecutorService metricScheduler) {
        try {
            metricScheduler.shutdown();
            String reportId = UUID.randomUUID().toString();
            TestReport report = reportService.generateReport(reportId, config, startTime, latencies, requestCounter.get(), errorCounter.get());

            log.info("Load test '{}' completed. Final Successes: {}, Final Errors: {}, Report ID: {}",
                    config.name(), requestCounter.get(), errorCounter.get(), reportId);

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
            emitter.completeWithError(e);
        }
    }

    private void sendProgressMetric(SseEmitter emitter, long now, long endTime,
                                    AtomicLong requestCounter, AtomicLong errorCounter,
                                    ScheduledExecutorService metricScheduler) {
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
    }

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
        return sb.toString();
    }
}
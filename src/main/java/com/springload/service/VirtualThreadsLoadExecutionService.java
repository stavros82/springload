package com.springload.service;

import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.util.DynamicVariableResolver;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Qualifier("virtualThreadEngine")
public class VirtualThreadsLoadExecutionService implements LoadExecutionService {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadsLoadExecutionService.class);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ReportService reportService;

    public VirtualThreadsLoadExecutionService(ReportService reportService) {
        this.reportService = reportService;
    }

    private record MetricsContext(
            SseEmitter emitter,
            StressConfig config,
            LocalDateTime startTime,
            long endTimeMillis,
            AtomicLong requestCounter,
            AtomicLong errorCounter,
            List<Long> latencies
    ) {}

    @Override
    public SseEmitter executeTest(StressConfig config) {
        log.info("Starting load test: '{}' targeting Base URL: '{}' with concurrency: {} for duration: {}s",
                config.name(), config.targetBaseUrl(), config.execution().concurrency(), config.execution().durationSeconds());

        SseEmitter emitter = new SseEmitter(0L);
        AtomicLong requestCounter = new AtomicLong();
        AtomicLong errorCounter = new AtomicLong();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        LocalDateTime startTime = LocalDateTime.now(ZoneId.systemDefault());
        long startMillis = System.currentTimeMillis();
        long endTime = startMillis + (config.execution().durationSeconds() * 1000L);

        startVirtualThreadExecutor(config, endTime, requestCounter, errorCounter, latencies);
        scheduleMetricsEmitter(emitter, config, startTime, endTime, requestCounter, errorCounter, latencies);

        return emitter;
    }

    private void startVirtualThreadExecutor(StressConfig config, long endTime,
                                            AtomicLong requestCounter, AtomicLong errorCounter,
                                            List<Long> latencies) {
        Thread.ofPlatform().name("load-generator-", 0).start(() -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                config.scenarios().stream()
                        .filter(s -> s.enabled() && s.isActive())
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
            String resolvedPath = DynamicVariableResolver.resolve(scenario.path());
            String fullUrl = baseUrl + resolvedPath;
            String method = scenario.method().toUpperCase();
            var resolvedHeaders = DynamicVariableResolver.resolveHeaders(scenario.headers());
            String resolvedBody = DynamicVariableResolver.resolve(scenario.body());

            try {
                HttpRequest request = buildHttpRequest(resolvedHeaders, resolvedBody, fullUrl, method);
                long reqStart = System.currentTimeMillis();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long reqDuration = System.currentTimeMillis() - reqStart;

                latencies.add(reqDuration);
                handleResponse(response, method, fullUrl, reqDuration, requestCounter, errorCounter);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                long currentErrors = errorCounter.incrementAndGet();
                log.error("EXECUTION ERROR dispatching to {} {}: {} (Total Errors: {})",
                        method, fullUrl, e.getMessage(), currentErrors, e);
            }
        }
    }

    private HttpRequest buildHttpRequest(Map<String, String> headers, String body, String fullUrl, String method) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(5));

        if (headers != null) {
            headers.forEach(builder::header);
        }

        HttpRequest.BodyPublisher bodyPublisher = (body != null && !body.isBlank())
                ? HttpRequest.BodyPublishers.ofString(body)
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
        MetricsContext context = new MetricsContext(
                emitter, config, startTime, endTime, requestCounter, errorCounter, latencies);

        Thread.ofPlatform().name("metrics-emitter").start(() -> {
            CountDownLatch finished = new CountDownLatch(1);
            Runnable finishMetrics = finished::countDown;
            emitter.onCompletion(finishMetrics);
            emitter.onTimeout(finishMetrics);
            emitter.onError(e -> finishMetrics.run());

            try (ScheduledExecutorService metricScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "metrics-emitter-worker");
                thread.setDaemon(true);
                return thread;
            })) {
                metricScheduler.scheduleAtFixedRate(() -> {
                    try {
                        long now = System.currentTimeMillis();
                        if (now >= endTime) {
                            handleTestCompletion(context);
                            finishMetrics.run();
                            return;
                        }
                        sendProgressMetric(context, now);
                    } catch (Exception e) {
                        log.error("Error in metrics emitter task: {}", e.getMessage());
                        finishMetrics.run();
                    }
                }, 1, 1, TimeUnit.SECONDS);

                long waitMillis = Math.max(endTime - System.currentTimeMillis(), 0) + 5000;
                if (!finished.await(waitMillis, TimeUnit.MILLISECONDS)) {
                    log.warn("Metrics emitter timed out waiting for completion for test '{}'", config.name());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Metrics emitter interrupted for test '{}'", config.name());
            }
        });
    }

    private void handleTestCompletion(MetricsContext context) {
        try {
            String reportId = UUID.randomUUID().toString();
            reportService.generateReport(
                    reportId, context.config(), context.startTime(),
                    context.latencies(), context.requestCounter().get(), context.errorCounter().get());

            log.info("Load test '{}' completed. Final Successes: {}, Final Errors: {}, Report ID: {}",
                    context.config().name(), context.requestCounter().get(),
                    context.errorCounter().get(), reportId);

            context.emitter().send(SseEmitter.event()
                    .name("complete")
                    .data(Map.of(
                            "message", "Test execution complete",
                            "reportId", reportId,
                            "reportUrl", "/report.html?id=" + reportId
                    )));
            context.emitter().complete();
        } catch (Exception e) {
            log.error("Failed to send SSE complete event: {}", e.getMessage());
            context.emitter().completeWithError(e);
        }
    }

    private void sendProgressMetric(MetricsContext context, long now) {
        try {
            long totalSuccess = context.requestCounter().get();
            long totalErrors = context.errorCounter().get();

            log.info("[PROGRESS] Time remaining: {}s | Successes: {} | Errors: {}",
                    (context.endTimeMillis() - now) / 1000, totalSuccess, totalErrors);

            context.emitter().send(SseEmitter.event()
                    .name("metric")
                    .data(Map.of(
                            "timestamp", now,
                            "successfulRequests", totalSuccess,
                            "failedRequests", totalErrors
                    )));
        } catch (IOException e) {
            log.error("SSE Connection disconnected by client: {}", e.getMessage());
            context.emitter().completeWithError(e);
        }
    }

}
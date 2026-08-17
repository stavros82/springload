package com.springload.service;

import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.TestReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Qualifier("reactiveEngine")
public class ReactiveLoadExecutionService implements LoadExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ReactiveLoadExecutionService.class);

    // Pattern to match URI path variables like {itemId} or {id}
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^/]+)\\}");

    // Headers restricted by Java HttpClient / Netty stack
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "connection", "content-length", "host", "user-agent",
            "upgrade", "expect", "trailers", "transfer-encoding"
    );

    private final WebClient webClient;
    private final ReportService reportService;

    public ReactiveLoadExecutionService(ReportService reportService) {
        this.reportService = reportService;
        this.webClient = WebClient.builder().build();
    }

    @Override
    public SseEmitter executeTest(StressConfig config) {
        log.info("[Reactive Engine] Starting non-blocking load test: '{}' targeting Base URL: '{}'",
                config.name(), config.targetBaseUrl());

        SseEmitter emitter = new SseEmitter(0L);
        AtomicLong requestCounter = new AtomicLong();
        AtomicLong errorCounter = new AtomicLong();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        LocalDateTime startTime = LocalDateTime.now();
        long durationSec = config.execution().durationSeconds();

        startReactiveExecutionPipeline(config, requestCounter, errorCounter, latencies, emitter, startTime, durationSec);
        scheduleRealTimeMetrics(emitter, requestCounter, errorCounter, latencies, durationSec);

        return emitter;
    }

    private void startReactiveExecutionPipeline(StressConfig config,
                                                AtomicLong requestCounter,
                                                AtomicLong errorCounter,
                                                List<Long> latencies,
                                                SseEmitter emitter,
                                                LocalDateTime startTime,
                                                long durationSec) {
        Flux.fromIterable(config.scenarios())
                .filter(ScenarioConfig::enabled)
                .flatMap(scenario -> createScenarioFlux(scenario, config, requestCounter, errorCounter, latencies, durationSec))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        error -> log.error("[Reactive Engine] Execution error stream", error),
                        () -> handleExecutionCompletion(config, emitter, startTime, latencies, requestCounter, errorCounter)
                );
    }

    private Flux<Void> createScenarioFlux(ScenarioConfig scenario,
                                          StressConfig config,
                                          AtomicLong requestCounter,
                                          AtomicLong errorCounter,
                                          List<Long> latencies,
                                          long durationSec) {
        int concurrency = config.execution().concurrency();
        return Flux.range(0, concurrency)
                .flatMap(i -> executeSingleRequest(scenario, config.targetBaseUrl(), requestCounter, errorCounter, latencies)
                        .repeat()
                        .take(Duration.ofSeconds(durationSec))
                );
    }

    private Mono<Void> executeSingleRequest(ScenarioConfig scenario,
                                            String targetBaseUrl,
                                            AtomicLong requestCounter,
                                            AtomicLong errorCounter,
                                            List<Long> latencies) {
        long reqStart = System.currentTimeMillis();
        String resolvedUri = resolvePathVariables(targetBaseUrl, scenario.path());

        var requestSpec = webClient.method(HttpMethod.valueOf(scenario.method().toUpperCase()))
                .uri(resolvedUri);

        applyAllowedHeaders(scenario, requestSpec);

        return requestSpec
                .exchangeToMono(response -> {
                    recordLatencyAndStatus(response.statusCode().is2xxSuccessful(), reqStart, latencies, requestCounter, errorCounter);
                    return response.releaseBody();
                })
                .onErrorResume(e -> {
                    errorCounter.incrementAndGet();
                    return Mono.empty();
                });
    }

    private void applyAllowedHeaders(ScenarioConfig scenario, WebClient.RequestHeadersSpec<?> requestSpec) {
        if (scenario.headers() != null) {
            scenario.headers().forEach((name, value) -> {
                if (name != null && !RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    requestSpec.header(name, value);
                }
            });
        }
    }

    private void recordLatencyAndStatus(boolean isSuccess,
                                        long reqStart,
                                        List<Long> latencies,
                                        AtomicLong requestCounter,
                                        AtomicLong errorCounter) {
        long latency = System.currentTimeMillis() - reqStart;
        latencies.add(latency);
        if (isSuccess) {
            requestCounter.incrementAndGet();
        } else {
            errorCounter.incrementAndGet();
        }
    }

    private void handleExecutionCompletion(StressConfig config,
                                           SseEmitter emitter,
                                           LocalDateTime startTime,
                                           List<Long> latencies,
                                           AtomicLong requestCounter,
                                           AtomicLong errorCounter) {
        try {
            String reportId = UUID.randomUUID().toString();
            TestReport report = reportService.generateReport(
                    reportId, config, startTime, latencies, requestCounter.get(), errorCounter.get()
            );

            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(Map.of(
                            "status", "COMPLETED",
                            "reportId", report.reportId(),
                            "reportUrl", "/api/v1/reports/" + report.reportId() + "/html"
                    )));
            emitter.complete();
        } catch (Exception ex) {
            log.error("Failed to complete SSE test stream", ex);
            emitter.completeWithError(ex);
        }
    }

    private void scheduleRealTimeMetrics(SseEmitter emitter,
                                         AtomicLong requestCounter,
                                         AtomicLong errorCounter,
                                         List<Long> latencies,
                                         long durationSec) {
        Flux.interval(Duration.ofSeconds(1))
                .take(Duration.ofSeconds(durationSec))
                .subscribe(tick -> {
                    try {
                        long success = requestCounter.get();
                        long failed = errorCounter.get();
                        long total = success + failed;

                        Map<String, Object> metricData = new HashMap<>();
                        metricData.put("timestamp", System.currentTimeMillis());
                        metricData.put("successfulRequests", success);
                        metricData.put("failedRequests", failed);
                        metricData.put("totalRequests", total);
                        metricData.put("p50", latencies.isEmpty() ? 0 : latencies.get(latencies.size() / 2));
                        metricData.put("p90", latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.9)));
                        metricData.put("p99", latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.99)));

                        emitter.send(SseEmitter.event().name("metric").data(metricData));
                    } catch (Exception e) {
                        // Client might have disconnected
                    }
                });
    }

    /**
     * Automatically extracts path variables like {itemId} and populates them with default fallback values.
     */
    private String resolvePathVariables(String baseUrl, String pathTemplate) {
        String fullUri = baseUrl + pathTemplate;
        Matcher matcher = PATH_PARAM_PATTERN.matcher(fullUri);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "1");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
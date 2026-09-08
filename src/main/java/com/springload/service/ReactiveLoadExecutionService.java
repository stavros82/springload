package com.springload.service;

import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.TestReport;
import com.springload.util.DynamicVariableResolver;
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
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Qualifier("reactiveEngine")
public class ReactiveLoadExecutionService implements LoadExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ReactiveLoadExecutionService.class);

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

        LocalDateTime startTime = LocalDateTime.now(ZoneId.systemDefault());
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
        List<ScenarioConfig> scenarios = config.scenarios().stream()
                .filter(s -> s.enabled() && s.isActive())
                .toList();
        Flux.range(0, config.execution().concurrency())
                .flatMap(i -> createFlowFlux(scenarios, config.targetBaseUrl(), requestCounter,
                        errorCounter, latencies, durationSec, emitter))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        error -> log.error("[Reactive Engine] Execution error stream", error),
                        () -> handleExecutionCompletion(config, emitter, startTime, latencies, requestCounter, errorCounter)
                );
    }

    private Flux<Void> createFlowFlux(List<ScenarioConfig> scenarios,
                                          String targetBaseUrl,
                                          AtomicLong requestCounter,
                                          AtomicLong errorCounter,
                                          List<Long> latencies,
                                          long durationSec,
                                          SseEmitter emitter) {
        return Flux.defer(() -> {
            Map<String, String> flowVariables = new HashMap<>();
            return Flux.fromIterable(scenarios)
                    .concatMap(scenario -> executeSingleRequest(
                            scenario, targetBaseUrl, flowVariables, requestCounter,
                            errorCounter, latencies, emitter))
                    .repeat()
                    .take(Duration.ofSeconds(durationSec));
        });
    }

    private Mono<Void> executeSingleRequest(ScenarioConfig scenario,
                                            String targetBaseUrl,
                                            Map<String, String> flowVariables,
                                            AtomicLong requestCounter,
                                            AtomicLong errorCounter,
                                            List<Long> latencies,
                                            SseEmitter emitter) {
        return Mono.defer(() -> {
            long reqStart = System.currentTimeMillis();
            String resolvedPath = DynamicVariableResolver.resolve(scenario.path(), flowVariables);
            String resolvedUri = DynamicVariableResolver.appendQueryParams(
                    targetBaseUrl + resolvedPath, scenario.queryParams(), flowVariables);
            var resolvedHeaders = DynamicVariableResolver.resolveHeaders(scenario.headers(), flowVariables);
            String resolvedBody = DynamicVariableResolver.resolve(scenario.body(), flowVariables);

            if (DynamicVariableResolver.hasUnresolvedVariables(resolvedPath)) {
                return Mono.error(new IllegalArgumentException("Unresolved flow variable in path '"
                        + scenario.path() + "'. Ensure the extracting scenario is enabled and runs before this request."));
            }
            WebClient.RequestBodySpec bodySpec = webClient
                    .method(HttpMethod.valueOf(scenario.method().toUpperCase()))
                    .uri(resolvedUri);

            applyAllowedHeaders(resolvedHeaders, bodySpec);

            WebClient.RequestHeadersSpec<?> requestSpec = (resolvedBody != null && !resolvedBody.isBlank())
                    ? bodySpec.bodyValue(resolvedBody)
                    : bodySpec;

            return requestSpec
                    .exchangeToMono(response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .doOnNext(body -> flowVariables.putAll(DynamicVariableResolver.extract(
                                    scenario.extractedVariables(), body, response.headers().asHttpHeaders())))
                            .doOnNext(body -> recordLatencyAndStatus(
                                    response.statusCode().is2xxSuccessful(), reqStart, latencies,
                                    requestCounter, errorCounter))
                            .then())
                    .onErrorResume(e -> {
                        errorCounter.incrementAndGet();
                        return Mono.empty();
                    });
        });
    }

    private void applyAllowedHeaders(Map<String, String> headers, WebClient.RequestHeadersSpec<?> requestSpec) {
        if (headers != null) {
            headers.forEach((name, value) -> {
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
}
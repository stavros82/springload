package com.springload.service;

import com.springload.dto.ScenarioSummary;
import com.springload.dto.StressConfig;
import com.springload.dto.TestReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private final Map<String, TestReport> reportStore = new ConcurrentHashMap<>();

    public TestReport generateReport(String reportId, StressConfig config, LocalDateTime startTime, 
                                     List<Long> latencies, long successCount, long errorCount) {
        log.info("Generating report [{}] for test '{}'...", reportId, config.name());
        LocalDateTime endTime = LocalDateTime.now(ZoneId.systemDefault());
        long durationSeconds = Math.max(1, config.execution().durationSeconds());
        long totalRequests = successCount + errorCount;
        double rps = (double) totalRequests / durationSeconds;

        double p50 = calculatePercentile(latencies, 50.0);
        double p95 = calculatePercentile(latencies, 95.0);
        double p99 = calculatePercentile(latencies, 99.0);

        double errorPercentage = totalRequests > 0 ? ((double) errorCount / totalRequests) * 100.0 : 0.0;
        
        boolean passedThresholds = true;
        if (config.thresholds() != null) {
            passedThresholds = p95 <= config.thresholds().p95LatencyMs() 
                            && errorPercentage <= config.thresholds().maxErrorPercentage();
        }


        List<ScenarioSummary> scenarioSummaries = new ArrayList<>();
        if (config.scenarios() != null && !config.scenarios().isEmpty()) {
            // Safely check if enabled is true (treating null as true by default)
            var activeScenarios = config.scenarios().stream()
                    .filter(s -> s.enabled() && s.isActive())
                    .toList();

            int scenarioCount = activeScenarios.size();

            if (scenarioCount > 0) {
                activeScenarios.forEach(s -> scenarioSummaries.add(new ScenarioSummary(
                        s.name(), s.method(), s.path(),
                        totalRequests / scenarioCount,
                        successCount / scenarioCount,
                        errorCount / scenarioCount,
                        p50, p95, p99, errorPercentage
                )));
            }
        }

        TestReport report = new TestReport(
                reportId, config.name(), startTime, endTime,
                durationSeconds, config.execution().concurrency(),
                totalRequests, successCount, errorCount, rps,
                p95, p99, passedThresholds, scenarioSummaries
        );

        reportStore.put(reportId, report);
        log.info("Report [{}] generated successfully. Status Passed: {}", reportId, passedThresholds);
        return report;
    }

    public TestReport getReport(String reportId) {
        return reportStore.get(reportId);
    }

    private double calculatePercentile(List<Long> latencies, double percentile) {
        if (latencies == null || latencies.isEmpty()) return 0.0;
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
    }
}
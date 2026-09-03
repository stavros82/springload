package com.springload.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.springload.dto.ExecutionSettings;
import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.Thresholds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class YamlParserStrategy implements StressConfigParserStrategy {

    private static final Logger log = LoggerFactory.getLogger(YamlParserStrategy.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]+)}");
    private static final Pattern COLON_SEGMENT = Pattern.compile("/:([A-Za-z0-9_-]+)");
    private static final Pattern DYNAMIC_EXPRESSION =
            Pattern.compile("random\\.uuid|timestamp|random\\(\\d+-\\d+\\)");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Override
    public ParserType getType() {
        return ParserType.YAML;
    }

    @Override
    public StressConfig parse(InputStream inputStream) {
        log.info("Starting YAML blueprint parsing...");
        try {
            // Jackson automatically deserializes the structured YAML into the StressConfig DTO tree
            StressConfig config = yamlMapper.readValue(inputStream, StressConfig.class);

            if (config == null) {
                log.error("Failed to parse YAML: Produced empty or null StressConfig object");
                throw new IllegalArgumentException("Invalid YAML configuration format.");
            }

            log.info("Successfully parsed YAML blueprint: '{}' with {} scenarios",
                    config.name(), config.scenarios() != null ? config.scenarios().size() : 0);

            // Apply defensive fallbacks in case execution or thresholds sections are omitted in the YAML
            ExecutionSettings execution = config.execution() != null 
                    ? config.execution() 
                    : new ExecutionSettings(50, 30, 5);

            Thresholds thresholds = config.thresholds() != null 
                    ? config.thresholds() 
                    : new Thresholds(200, 500, 1.0);

            List<ScenarioConfig> scenarios = config.scenarios() != null
                    ? config.scenarios().stream().map(YamlParserStrategy::withNormalizedPath).toList()
                    : new ArrayList<ScenarioConfig>();

            return new StressConfig(
                    config.name() != null ? config.name() : "Imported YAML Blueprint",
                    config.targetBaseUrl() != null ? config.targetBaseUrl() : "http://localhost:8080",
                    execution,
                    scenarios,
                    thresholds
            );

        } catch (Exception e) {
            log.error("Error parsing YAML stress configuration: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse YAML stress configuration: " + e.getMessage(), e);
        }
    }

    private static ScenarioConfig withNormalizedPath(ScenarioConfig scenario) {
        String normalized = normalizePath(scenario.path());
        if (normalized == null || normalized.equals(scenario.path())) {
            return scenario;
        }
        return new ScenarioConfig(
                scenario.name(),
                scenario.method(),
                normalized,
                scenario.weight(),
                scenario.headers(),
                scenario.queryParams(),
                scenario.body(),
                scenario.enabled(),
                scenario.active(),
                scenario.extractedVariables()
        );
    }

    /**
     * Path parameters are exposed as bindable {@code {token}} placeholders, matching the other parser
     * strategies, so the Scenario Inspector can substitute them without leaving stray {@code $} prefixes.
     * Resolver expressions such as {@code ${random.uuid}} keep their {@code ${...}} form.
     */
    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        String normalized = COLON_SEGMENT.matcher(path).replaceAll("/{$1}");
        if (!normalized.contains("${")) {
            return normalized;
        }
        Matcher matcher = PLACEHOLDER.matcher(normalized);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1).trim();
            String replacement = DYNAMIC_EXPRESSION.matcher(token).matches()
                    ? "${" + token + "}"
                    : "{" + token + "}";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
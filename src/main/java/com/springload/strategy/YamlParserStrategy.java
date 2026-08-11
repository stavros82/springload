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

@Component
public class YamlParserStrategy implements StressConfigParserStrategy {

    private static final Logger log = LoggerFactory.getLogger(YamlParserStrategy.class);
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
                    ? config.scenarios() 
                    : new ArrayList<>();

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
}
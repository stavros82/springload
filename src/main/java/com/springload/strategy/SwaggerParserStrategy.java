package com.springload.strategy;

import com.springload.dto.ExecutionSettings;
import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.Thresholds;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class SwaggerParserStrategy implements StressConfigParserStrategy {

    @Override
    public ParserType getType() {
        return ParserType.SWAGGER;
    }

    @Override
    public StressConfig parse(InputStream inputStream) {
        try {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            OpenAPI openAPI = new OpenAPIV3Parser().readContents(content).getOpenAPI();

            if (openAPI == null) {
                throw new IllegalArgumentException("Invalid Swagger/OpenAPI specification");
            }

            List<ScenarioConfig> scenarios = new ArrayList<>();
            String baseUrl = "http://localhost:8080";
            if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
                baseUrl = openAPI.getServers().get(0).getUrl();
            }

            if (openAPI.getPaths() != null) {
                openAPI.getPaths().forEach((path, item) -> {
                    item.readOperationsMap().forEach((method, operation) -> {
                        scenarios.add(new ScenarioConfig(
                            operation.getSummary() != null ? operation.getSummary() : method.name() + " " + path,
                            method.name(),
                            path,
                            1,
                            Map.of("Content-Type", "application/json"),
                            Collections.emptyMap(),
                            null,
                            true
                        ));
                    });
                });
            }

            return new StressConfig(
                openAPI.getInfo() != null ? openAPI.getInfo().getTitle() : "Swagger Import",
                baseUrl,
                new ExecutionSettings(50, 30, 5),
                scenarios,
                new Thresholds(200, 500, 1.0)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Swagger/OpenAPI specification", e);
        }
    }
}
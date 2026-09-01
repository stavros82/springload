package com.springload.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springload.dto.ExecutionSettings;
import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.Thresholds;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

@Component
public class SwaggerParserStrategy implements StressConfigParserStrategy {

    private static final String APPLICATION_JSON = "application/json";
    private static final String SAMPLE_PREFIX = "sample_";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ParserType getType() {
        return ParserType.SWAGGER;
    }

    @Override
    public StressConfig parse(InputStream inputStream) {
        try {
            String specContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            ParseOptions parseOptions = new ParseOptions();
            parseOptions.setResolve(true);
            parseOptions.setResolveFully(true);

            SwaggerParseResult parseResult = new OpenAPIV3Parser().readContents(specContent, null, parseOptions);
            OpenAPI openAPI = parseResult.getOpenAPI();

            if (openAPI == null) {
                throw new IllegalArgumentException("Failed to parse OpenAPI specification");
            }

            String baseUrl = extractBaseUrl(openAPI);
            List<ScenarioConfig> scenarios = extractScenarios(openAPI);

            String title = (openAPI.getInfo() != null && openAPI.getInfo().getTitle() != null)
                    ? openAPI.getInfo().getTitle()
                    : "Generic OpenAPI Blueprint";

            return new StressConfig(
                    title,
                    baseUrl,
                    new ExecutionSettings(50, 30, 5),
                    scenarios,
                    new Thresholds(200, 500, 1.0)
            );
        } catch (Exception e) {
            throw new RuntimeException("Error parsing OpenAPI specification", e);
        }
    }

    private String extractBaseUrl(OpenAPI openAPI) {
        if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
            String url = openAPI.getServers().getFirst().getUrl();
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return "http://localhost:8080";
    }

    private List<ScenarioConfig> extractScenarios(OpenAPI openAPI) {
        List<ScenarioConfig> scenarios = new ArrayList<>();
        if (openAPI.getPaths() == null) return scenarios;

        openAPI.getPaths().forEach((path, pathItem) -> {
            if (pathItem == null) return;
            Map<PathItem.HttpMethod, Operation> operations = pathItem.readOperationsMap();
            operations.forEach((httpMethod, operation) -> {
                if (operation != null) {
                    scenarios.add(buildScenario(path, httpMethod, operation, openAPI));
                }
            });
        });

        return scenarios;
    }

    private ScenarioConfig buildScenario(String rawPath, PathItem.HttpMethod httpMethod, Operation operation, OpenAPI openAPI) {
        String method = httpMethod.name().toUpperCase();
        Map<String, String> headers = new HashMap<>();
        Map<String, String> queryParams = new HashMap<>();

        headers.put("Accept", APPLICATION_JSON);
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            headers.put("Content-Type", APPLICATION_JSON);
        }

        String resolvedPath = resolvePathAndParameters(rawPath, operation, openAPI, headers, queryParams);
        String bodyJson = extractRequestBody(operation, openAPI);
        Map<String, String> extractedVariables = extractResponseBindings(operation);

        String scenarioTitle = (operation.getSummary() != null && !operation.getSummary().isBlank())
                ? operation.getSummary()
                : method + " " + resolvedPath;

        return new ScenarioConfig(
                scenarioTitle,
                method,
                resolvedPath,
                1,
                headers,
                queryParams,
                bodyJson,
                true,
                true,
                extractedVariables
        );


    }

    private String resolvePathAndParameters(String rawPath, Operation operation, OpenAPI openAPI, Map<String, String> headers, Map<String, String> queryParams) {
        if (operation.getParameters() == null) return rawPath;

        for (Parameter param : operation.getParameters()) {
            if (param == null || param.getName() == null) continue;

            String name = param.getName();
            Object val = extractValueFromSchema(name, param.getSchema(), param.getExample(), openAPI, 0);
            String strVal = String.valueOf(val);

            if ("header".equalsIgnoreCase(param.getIn()) && !isRestrictedHeader(name)) {
                headers.putIfAbsent(name, strVal);
            } else if ("query".equalsIgnoreCase(param.getIn())) {
                queryParams.put(name, strVal);
            }
            // Path parameters are intentionally left as standard raw tokens (e.g., {vetId})
            // to allow the load execution service to safely replace them without leaving stray '$' prefixes.
        }
        return rawPath;
    }

    private String extractRequestBody(Operation operation, OpenAPI openAPI) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return null;
        }

        MediaType mediaType = operation.getRequestBody().getContent().get(APPLICATION_JSON);
        if (mediaType == null) {
            var firstEntry = operation.getRequestBody().getContent().entrySet().stream().findFirst();
            if (firstEntry.isPresent()) {
                mediaType = firstEntry.get().getValue();
            }
        }

        if (mediaType == null || mediaType.getSchema() == null) {
            return null;
        }

        Map<String, Object> mockPayload = buildMockPayload(mediaType.getSchema(), openAPI, 0);
        if (mockPayload.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(mockPayload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> buildMockPayload(Schema<?> schema, OpenAPI openAPI, int depth) {
        if (depth > 5 || schema == null) return Collections.emptyMap();

        Schema<?> target = resolveSchema(schema, openAPI);
        if (target == null || target.getProperties() == null) return Collections.emptyMap();

        Map<String, Object> map = new LinkedHashMap<>();
        target.getProperties().forEach((propName, propSchema) ->
                map.put(propName, extractValueFromSchema(propName, propSchema, null, openAPI, depth + 1))
        );
        return map;
    }

    private Object extractValueFromSchema(String fieldName, Schema<?> schema, Object explicitExample, OpenAPI openAPI, int depth) {
        if (depth > 6) return SAMPLE_PREFIX + fieldName;

        Schema<?> target = resolveSchema(schema, openAPI);
        if (target == null) return SAMPLE_PREFIX + fieldName;

        if (explicitExample != null) return explicitExample;
        if (target.getExample() != null) return target.getExample();
        if (target.getDefault() != null) return target.getDefault();

        if (target.getEnum() != null && !target.getEnum().isEmpty()) {
            return target.getEnum().getFirst();
        }

        String type = (target.getType() != null) ? target.getType().toLowerCase() : "string";
        String format = (target.getFormat() != null) ? target.getFormat().toLowerCase() : "";

        if ("uuid".equals(format)) {
            return UUID.randomUUID().toString();
        }

        return switch (type) {
            case "integer", "int" -> target.getMinimum() != null ? target.getMinimum().longValue() : 1L;
            case "number", "float", "double" -> target.getMinimum() != null ? target.getMinimum().doubleValue() : 10.5;
            case "boolean" -> true;
            case "array" -> {
                Schema<?> itemSchema = target.getItems();
                Object itemVal = (itemSchema != null) ? extractValueFromSchema(fieldName, itemSchema, null, openAPI, depth + 1) : "sample";
                yield (itemVal != null) ? List.of(itemVal) : Collections.emptyList();
            }
            case "object" -> buildMockPayload(target, openAPI, depth + 1);
            default -> deriveFormattedString(fieldName, format, target);
        };
    }

    private String deriveFormattedString(String fieldName, String format, Schema<?> target) {
        if ("date".equals(format) || fieldName.toLowerCase().contains("date")) {
            return LocalDate.now(ZoneId.systemDefault()).toString();
        }
        if ("date-time".equals(format)) {
            return OffsetDateTime.now(ZoneId.systemDefault()).toString();
        }
        if ("email".equals(format) || fieldName.toLowerCase().contains("email")) return "user@example.com";
        if ("uri".equals(format) || "url".equals(format)) return "https://example.com";

        if (target != null && target.getMinLength() != null && target.getMinLength() > 10) {
            return "sample_value_long_" + fieldName;
        }
        return SAMPLE_PREFIX + fieldName;
    }

    private Map<String, String> extractResponseBindings(Operation operation) {
        Map<String, String> bindings = new HashMap<>();
        if (operation.getResponses() == null) return bindings;

        var successResponse = operation.getResponses().get("200");
        if (successResponse == null) successResponse = operation.getResponses().get("201");

        if (successResponse != null && successResponse.getContent() != null) {
            MediaType mediaType = successResponse.getContent().get(APPLICATION_JSON);
            if (mediaType != null && mediaType.getSchema() != null
                    && mediaType.getSchema().getProperties() != null
                    && mediaType.getSchema().getProperties().containsKey("id")) {
                bindings.put("extractedId", "$.id");
            }
        }
        return bindings;
    }

    private Schema<?> resolveSchema(Schema<?> schema, OpenAPI openAPI) {
        if (schema != null && schema.get$ref() != null) {
            String refName = schema.get$ref()
                    .replace("#/components/schemas/", "")
                    .replace("#/definitions/", "");
            if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
                return openAPI.getComponents().getSchemas().get(refName);
            }
        }
        return schema;
    }

    private boolean isRestrictedHeader(String headerName) {
        if (headerName == null) return true;
        String lower = headerName.trim().toLowerCase();
        return lower.startsWith(":") || lower.equals("connection") || lower.equals("content-length")
                || lower.equals("host") || lower.equals("upgrade") || lower.equals("transfer-encoding");
    }
}
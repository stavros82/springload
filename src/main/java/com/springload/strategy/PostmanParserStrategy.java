package com.springload.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springload.dto.ExecutionSettings;
import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.Thresholds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ingests Postman Collection exports (schema v2.0.0 and v2.1.0), recursively traversing
 * the folder tree and translating Postman's {@code {{variable}}} syntax into the engine's
 * {@code ${variable}} placeholder format.
 */
@Component
public class PostmanParserStrategy implements StressConfigParserStrategy {

    private static final Logger log = LoggerFactory.getLogger(PostmanParserStrategy.class);

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";
    private static final Pattern POSTMAN_VARIABLE = Pattern.compile("\\{\\{\\s*([^{}\\s]+)\\s*}}");
    private static final Map<String, String> DYNAMIC_VARIABLE_ALIASES = Map.of(
            "$guid", "${random.uuid}",
            "$randomUUID", "${random.uuid}",
            "$timestamp", "${timestamp}",
            "$isoTimestamp", "${timestamp}",
            "$randomInt", "${random(1-1000)}"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ParserType getType() {
        return ParserType.POSTMAN;
    }

    @Override
    public StressConfig parse(InputStream inputStream) {
        try {
            JsonNode root = objectMapper.readTree(inputStream);
            if (root == null || !root.hasNonNull("item")) {
                throw new IllegalArgumentException("Invalid Postman collection: missing 'item' array.");
            }

            JsonNode info = root.path("info");
            String name = info.path("name").asText("Imported Postman Collection");

            Map<String, String> collectionVariables = extractCollectionVariables(root.path("variable"));

            List<ScenarioConfig> scenarios = new ArrayList<>();
            traverseItems(root.path("item"), "", scenarios);

            BaseUrl baseUrl = resolveBaseUrl(collectionVariables, scenarios);
            List<ScenarioConfig> normalized = stripBaseUrl(scenarios, baseUrl);

            log.info("Parsed Postman collection '{}' ({}) with {} scenarios",
                    name, info.path("schema").asText("unknown schema"), normalized.size());

            return new StressConfig(
                    name,
                    baseUrl.value(),
                    new ExecutionSettings(50, 30, 5),
                    normalized,
                    new Thresholds(200, 500, 1.0)
            );
        } catch (Exception e) {
            log.error("Error parsing Postman collection: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse Postman collection: " + e.getMessage(), e);
        }
    }

    private Map<String, String> extractCollectionVariables(JsonNode variables) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (!variables.isArray()) {
            return resolved;
        }
        for (JsonNode variable : variables) {
            String key = variable.path("key").asText(null);
            if (key != null && !key.isBlank()) {
                resolved.put(key, variable.path("value").asText(""));
            }
        }
        return resolved;
    }

    /** Walks the collection tree; folders are nested {@code item} arrays and may nest arbitrarily deep. */
    private void traverseItems(JsonNode items, String folderPath, List<ScenarioConfig> scenarios) {
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            String itemName = item.path("name").asText("");
            if (item.hasNonNull("item")) {
                String nested = folderPath.isEmpty() ? itemName : folderPath + " / " + itemName;
                traverseItems(item.path("item"), nested, scenarios);
            } else if (item.hasNonNull("request")) {
                ScenarioConfig scenario = buildScenario(item, folderPath);
                if (scenario != null) {
                    scenarios.add(scenario);
                }
            }
        }
    }

    private ScenarioConfig buildScenario(JsonNode item, String folderPath) {
        JsonNode request = item.path("request");
        String url = extractUrl(request.path("url"));
        if (url.isBlank()) {
            return null;
        }

        String method = request.path("method").asText("GET").toUpperCase();
        Map<String, String> headers = extractHeaders(request.path("header"));
        applyAuthHeader(request.path("auth"), headers);
        Map<String, String> queryParams = extractQueryParams(request.path("url"));
        String body = extractBody(request.path("body"), headers);

        String itemName = item.path("name").asText(method + " " + url);
        String scenarioName = folderPath.isEmpty() ? itemName : folderPath + " / " + itemName;

        return new ScenarioConfig(
                scenarioName,
                method,
                translate(url),
                1,
                headers,
                queryParams,
                body,
                true,
                true,
                new HashMap<>()
        );
    }

    /** Postman v2.0.0 stores the url as a plain string; v2.1.0 as an object with {@code raw}/{@code host}/{@code path}. */
    private String extractUrl(JsonNode urlNode) {
        if (urlNode.isTextual()) {
            return stripQuery(urlNode.asText());
        }
        if (urlNode.hasNonNull("raw")) {
            return stripQuery(urlNode.path("raw").asText());
        }
        StringBuilder sb = new StringBuilder();
        JsonNode host = urlNode.path("host");
        String hostValue = "";
        if (host.isArray()) {
            List<String> segments = new ArrayList<>();
            host.forEach(segment -> segments.add(segment.asText()));
            hostValue = String.join(".", segments);
        } else if (host.isTextual()) {
            hostValue = host.asText();
        }
        if (!hostValue.isEmpty() && !hostValue.contains("{{") && !hostValue.contains("://")) {
            sb.append(urlNode.path("protocol").asText("http")).append("://");
        }
        sb.append(hostValue);
        JsonNode path = urlNode.path("path");
        if (path.isArray()) {
            for (JsonNode segment : path) {
                sb.append('/').append(segment.asText());
            }
        } else if (path.isTextual()) {
            sb.append(path.asText().startsWith("/") ? "" : "/").append(path.asText());
        }
        return sb.toString();
    }

    private String stripQuery(String url) {
        int idx = url.indexOf('?');
        return idx >= 0 ? url.substring(0, idx) : url;
    }

    private Map<String, String> extractHeaders(JsonNode headerNode) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!headerNode.isArray()) {
            return headers;
        }
        for (JsonNode header : headerNode) {
            if (header.path("disabled").asBoolean(false)) {
                continue;
            }
            String key = header.path("key").asText(null);
            if (key == null || key.isBlank() || isRestrictedHeader(key)) {
                continue;
            }
            headers.put(key, translate(header.path("value").asText("")));
        }
        return headers;
    }

    private void applyAuthHeader(JsonNode auth, Map<String, String> headers) {
        if (auth.isMissingNode() || auth.isNull()) {
            return;
        }
        String type = auth.path("type").asText("");
        if (!"bearer".equalsIgnoreCase(type)) {
            return;
        }
        JsonNode bearer = auth.path("bearer");
        String token = "";
        if (bearer.isArray()) {
            for (JsonNode entry : bearer) {
                if ("token".equals(entry.path("key").asText())) {
                    token = entry.path("value").asText("");
                }
            }
        } else if (bearer.isObject()) {
            token = bearer.path("token").asText("");
        }
        if (!token.isBlank()) {
            headers.putIfAbsent("Authorization", "Bearer " + translate(token));
        }
    }

    private Map<String, String> extractQueryParams(JsonNode urlNode) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        JsonNode query = urlNode.path("query");
        if (query.isArray()) {
            for (JsonNode param : query) {
                if (param.path("disabled").asBoolean(false)) {
                    continue;
                }
                String key = param.path("key").asText(null);
                if (key != null && !key.isBlank()) {
                    queryParams.put(key, translate(param.path("value").asText("")));
                }
            }
            return queryParams;
        }

        String raw = urlNode.isTextual() ? urlNode.asText() : urlNode.path("raw").asText("");
        int idx = raw.indexOf('?');
        if (idx < 0) {
            return queryParams;
        }
        for (String pair : raw.substring(idx + 1).split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            queryParams.put(parts[0], translate(parts.length > 1 ? parts[1] : ""));
        }
        return queryParams;
    }

    private String extractBody(JsonNode bodyNode, Map<String, String> headers) {
        if (bodyNode.isMissingNode() || bodyNode.isNull()) {
            return null;
        }
        String mode = bodyNode.path("mode").asText("");
        return switch (mode) {
            case "raw" -> {
                String raw = bodyNode.path("raw").asText("");
                if (raw.isBlank()) {
                    yield null;
                }
                headers.putIfAbsent("Content-Type", rawContentType(bodyNode));
                yield translate(raw);
            }
            case "urlencoded", "formdata" -> {
                JsonNode entries = bodyNode.path(mode);
                if (!entries.isArray() || entries.isEmpty()) {
                    yield null;
                }
                List<String> pairs = new ArrayList<>();
                for (JsonNode entry : entries) {
                    if (entry.path("disabled").asBoolean(false) || "file".equals(entry.path("type").asText())) {
                        continue;
                    }
                    pairs.add(entry.path("key").asText("") + "=" + translate(entry.path("value").asText("")));
                }
                if (pairs.isEmpty()) {
                    yield null;
                }
                headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
                yield String.join("&", pairs);
            }
            default -> null;
        };
    }

    private String rawContentType(JsonNode bodyNode) {
        String language = bodyNode.path("options").path("raw").path("language").asText("json");
        return switch (language) {
            case "xml" -> "application/xml";
            case "text" -> "text/plain";
            case "html" -> "text/html";
            default -> "application/json";
        };
    }

    /** Converts Postman placeholders ({@code {{token}}}) into the engine's {@code ${token}} syntax. */
    String translate(String value) {
        if (value == null || value.isEmpty() || !value.contains("{{")) {
            return value;
        }
        Matcher matcher = POSTMAN_VARIABLE.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String variable = matcher.group(1);
            String replacement = DYNAMIC_VARIABLE_ALIASES.getOrDefault(variable, "${" + variable + "}");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** The collection-level origin plus the placeholder (if any) that stands in for it inside request urls. */
    private record BaseUrl(String value, String placeholder) {}

    private BaseUrl resolveBaseUrl(Map<String, String> collectionVariables, List<ScenarioConfig> scenarios) {
        for (Map.Entry<String, String> entry : collectionVariables.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if ((key.equals("baseurl") || key.equals("base_url") || key.equals("url") || key.equals("host"))
                    && entry.getValue() != null && !entry.getValue().isBlank()) {
                return new BaseUrl(trimTrailingSlash(translate(entry.getValue())), "${" + entry.getKey() + "}");
            }
        }
        for (ScenarioConfig scenario : scenarios) {
            String origin = extractOrigin(scenario.path());
            if (origin != null) {
                return new BaseUrl(origin, null);
            }
        }
        return new BaseUrl(DEFAULT_BASE_URL, null);
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String extractOrigin(String url) {
        int schemeIdx = url.indexOf("://");
        if (schemeIdx < 0) {
            return null;
        }
        int pathIdx = url.indexOf('/', schemeIdx + 3);
        return pathIdx < 0 ? url : url.substring(0, pathIdx);
    }

    /** Scenario paths are stored relative to the resolved base url so the engine can retarget the run. */
    private List<ScenarioConfig> stripBaseUrl(List<ScenarioConfig> scenarios, BaseUrl baseUrl) {
        List<ScenarioConfig> normalized = new ArrayList<>(scenarios.size());
        for (ScenarioConfig scenario : scenarios) {
            normalized.add(withPath(scenario, toRelativePath(scenario.path(), baseUrl)));
        }
        return normalized;
    }

    private String toRelativePath(String url, BaseUrl baseUrl) {
        String path = url;
        if (baseUrl.placeholder() != null && path.startsWith(baseUrl.placeholder())) {
            path = path.substring(baseUrl.placeholder().length());
        } else if (path.startsWith(baseUrl.value())) {
            path = path.substring(baseUrl.value().length());
        } else {
            String origin = extractOrigin(path);
            if (origin != null) {
                path = path.substring(origin.length());
            }
        }
        if (path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private ScenarioConfig withPath(ScenarioConfig scenario, String path) {
        return new ScenarioConfig(
                scenario.name(),
                scenario.method(),
                path,
                scenario.weight(),
                scenario.headers(),
                scenario.queryParams(),
                scenario.body(),
                scenario.enabled(),
                scenario.active(),
                scenario.extractedVariables()
        );
    }

    private boolean isRestrictedHeader(String headerName) {
        String lower = headerName.trim().toLowerCase();
        return lower.startsWith(":") || lower.equals("connection") || lower.equals("content-length")
                || lower.equals("host") || lower.equals("upgrade") || lower.equals("transfer-encoding");
    }
}

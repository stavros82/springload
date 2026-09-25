package com.springload.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springload.dto.ExecutionSettings;
import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.Thresholds;
import com.springload.util.correlation.ExtractorPathBuilder;
import com.springload.util.correlation.HarCorrelationScanner;
import com.springload.util.correlation.UniquenessScorer;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class HarParserStrategy implements StressConfigParserStrategy {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "host",
            "content-length",
            "connection",
            "accept-encoding",
            "cache-control",
            "cookie",
            "set-cookie",
            "transfer-encoding",
            "upgrade-insecure-requests",
            "referer",
            "origin"
    );

    @Override
    public ParserType getType() {
        return ParserType.HAR;
    }

    @Override
    public StressConfig parse(InputStream inputStream) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(inputStream);
            JsonNode log = root.path("log");
            if (log.isMissingNode()) {
                throw new IllegalArgumentException("Invalid HAR file: missing 'log' object.");
            }

            JsonNode entries = log.path("entries");
            if (!entries.isArray()) {
                throw new IllegalArgumentException("Invalid HAR file: missing 'log.entries' array.");
            }

            List<HarCorrelationScanner.HarEntry> harEntries = new ArrayList<>();
            List<ScenarioConfig> scenarios = new ArrayList<>();
            String baseUrl = null;
            String harName = "Imported HAR";

            for (JsonNode entry : entries) {
                if (entry == null || entry.isNull()) {
                    continue;
                }
                JsonNode requestNode = entry.path("request");
                if (requestNode.isMissingNode()) {
                    continue;
                }

                String requestUrl = requestNode.path("url").asText(null);
                if (requestUrl == null || requestUrl.isBlank()) {
                    continue;
                }
                if (HarCorrelationScanner.isStaticAssetPath(requestUrl)) {
                    continue;
                }

                String method = requestNode.path("method").asText("GET").toUpperCase(Locale.ROOT);
                String path = normalizePath(requestUrl);
                if (baseUrl == null) {
                    baseUrl = extractBaseUrl(requestUrl);
                }

                Map<String, String> headers = extractHeaders(requestNode.path("headers"));
                Map<String, String> queryParams = extractQueryParams(requestNode.path("queryString"));
                String body = extractBody(requestNode.path("postData"));
                String name = method + " " + path;

                HarCorrelationScanner.HarEntry harEntry = new HarCorrelationScanner.HarEntry(
                        requestUrl,
                        queryParams,
                        headers,
                        body,
                        extractResponseBody(entry.path("response")),
                        extractResponseHeaders(entry.path("response"))
                );
                harEntries.add(harEntry);

                scenarios.add(new ScenarioConfig(
                        name,
                        method,
                        path,
                        1,
                        headers,
                        queryParams,
                        body,
                        true,
                        true,
                        Map.of(),
                        null,
                        null,
                        null
                ));
            }

            if (scenarios.isEmpty()) {
                throw new IllegalArgumentException("No valid HTTP requests were found in the HAR file.");
            }

            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://localhost:8080";
            }

            scenarios = applyCorrelationRules(scenarios, harEntries);

            return new StressConfig(
                    harName,
                    baseUrl,
                    new ExecutionSettings(50, 30, 5),
                    scenarios,
                    new Thresholds(200, 500, 1.0)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse HAR file: " + e.getMessage(), e);
        }
    }

    private List<ScenarioConfig> applyCorrelationRules(List<ScenarioConfig> scenarios, List<HarCorrelationScanner.HarEntry> harEntries) {
        if (scenarios == null || scenarios.isEmpty() || harEntries == null || harEntries.isEmpty()) {
            return scenarios;
        }

        Map<String, Integer> scenarioIndexByUrl = new LinkedHashMap<>();
        for (int i = 0; i < scenarios.size(); i++) {
            String requestUrl = harEntries.get(i).requestUrl();
            if (requestUrl != null && !requestUrl.isBlank()) {
                scenarioIndexByUrl.put(requestUrl, i);
            }
        }

        List<HarCorrelationScanner.CorrelationMatch> matches = HarCorrelationScanner.scan(harEntries);
        List<ScenarioConfig> resolved = new ArrayList<>(scenarios);

        for (HarCorrelationScanner.CorrelationMatch match : matches) {
            String sourceUrl = findSourceUrlForValue(harEntries, match.value());
            Integer upstreamIndex = sourceUrl == null ? null : scenarioIndexByUrl.get(sourceUrl);
            Integer downstreamIndex = scenarioIndexByUrl.get(match.requestUrl());

            if (upstreamIndex == null || downstreamIndex == null) {
                continue;
            }

            ScenarioConfig upstream = resolved.get(upstreamIndex);
            ScenarioConfig downstream = resolved.get(downstreamIndex);
            String variableName = inferVariableName(match.parameterName(), match.parameterValue(), match.value());

            String selector = buildExtractionSelector(harEntries, sourceUrl, match.value());
            Map<String, String> upstreamExtracts = new LinkedHashMap<>(upstream.extractedVariables() == null ? Map.of() : upstream.extractedVariables());
            if (selector != null && !selector.isBlank()) {
                upstreamExtracts.put(variableName, selector);
            }
            resolved.set(upstreamIndex, withExtractedVariables(upstream, upstreamExtracts));

            downstream = replaceDownstreamValue(downstream, variableName, match.parameterName(), match.parameterValue());
            resolved.set(downstreamIndex, downstream);
        }
        return resolved;
    }

    private String findSourceUrlForValue(List<HarCorrelationScanner.HarEntry> harEntries, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (HarCorrelationScanner.HarEntry entry : harEntries) {
            if (entry == null || entry.responseBody() == null || entry.responseBody().isBlank()) {
                continue;
            }
            if (entry.responseBody().contains(value)) {
                return entry.requestUrl();
            }
        }
        return null;
    }

    private String buildExtractionSelector(List<HarCorrelationScanner.HarEntry> harEntries, String sourceUrl, String value) {
        if (sourceUrl == null) {
            return ExtractorPathBuilder.buildRegexFallback(value);
        }
        for (HarCorrelationScanner.HarEntry entry : harEntries) {
            if (entry != null && sourceUrl.equals(entry.requestUrl())) {
                String jsonPath = ExtractorPathBuilder.buildJsonPath(entry.responseBody(), value);
                if (jsonPath != null && !jsonPath.isBlank()) {
                    return jsonPath;
                }
                for (Map.Entry<String, List<String>> responseHeader : entry.responseHeaders().entrySet()) {
                    for (String headerValue : responseHeader.getValue()) {
                        if (value.equals(headerValue)) {
                            return "header:" + responseHeader.getKey();
                        }
                    }
                }
                return ExtractorPathBuilder.buildRegexFallback(value);
            }
        }
        return ExtractorPathBuilder.buildRegexFallback(value);
    }

    private String inferVariableName(String parameterName, String parameterValue, String value) {
        String candidate = parameterName != null && !parameterName.isBlank() ? parameterName : parameterValue;
        if (candidate == null || candidate.isBlank()) {
            candidate = value;
        }
        String trimmed = candidate.replaceAll("[^A-Za-z0-9_]", "_");
        if (trimmed.isBlank()) {
            return "correlatedValue";
        }
        if (!Character.isLetter(trimmed.charAt(0))) {
            trimmed = "v_" + trimmed;
        }
        return trimmed;
    }

    private ScenarioConfig withExtractedVariables(ScenarioConfig scenario, Map<String, String> extractedVariables) {
        return new ScenarioConfig(
                scenario.name(),
                scenario.method(),
                scenario.path(),
                scenario.weight(),
                scenario.headers(),
                scenario.queryParams(),
                scenario.body(),
                scenario.enabled(),
                scenario.active(),
                extractedVariables,
                scenario.pathTemplate(),
                scenario.bodyTemplate(),
                scenario.variableOverrides()
        );
    }

    private ScenarioConfig replaceDownstreamValue(ScenarioConfig scenario, String variableName, String parameterName, String matchedValue) {
        Map<String, String> headers = new LinkedHashMap<>(scenario.headers() == null ? Map.of() : scenario.headers());
        Map<String, String> queryParams = new LinkedHashMap<>(scenario.queryParams() == null ? Map.of() : scenario.queryParams());
        String body = scenario.body();
        String path = scenario.path();

        if (parameterName != null && !parameterName.isBlank() && queryParams.containsKey(parameterName)) {
            queryParams.put(parameterName, "${" + variableName + "}");
        } else {
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (entry.getValue() != null && entry.getValue().equals(matchedValue)) {
                    queryParams.put(entry.getKey(), "${" + variableName + "}");
                }
            }
        }

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getValue() != null && entry.getValue().equals(matchedValue)) {
                headers.put(entry.getKey(), "${" + variableName + "}");
            }
        }

        if (body != null && body.contains(matchedValue)) {
            body = body.replace(matchedValue, "${" + variableName + "}");
        }
        if (path != null && path.contains(matchedValue)) {
            path = path.replace(matchedValue, "${" + variableName + "}");
        }

        return new ScenarioConfig(
                scenario.name(),
                scenario.method(),
                path,
                scenario.weight(),
                headers,
                queryParams,
                body,
                scenario.enabled(),
                scenario.active(),
                scenario.extractedVariables(),
                scenario.pathTemplate(),
                scenario.bodyTemplate(),
                scenario.variableOverrides()
        );
    }

    private String extractResponseBody(JsonNode responseNode) {
        if (responseNode == null || responseNode.isMissingNode() || responseNode.isNull()) {
            return null;
        }
        JsonNode content = responseNode.path("content");
        return content.path("text").asText(null);
    }

    private Map<String, List<String>> extractResponseHeaders(JsonNode responseNode) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (responseNode == null || responseNode.isMissingNode() || responseNode.isNull()) {
            return result;
        }
        JsonNode headers = responseNode.path("headers");
        if (!headers.isArray()) {
            return result;
        }
        for (JsonNode header : headers) {
            String name = header.path("name").asText(null);
            String value = header.path("value").asText(null);
            if (name == null || name.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private Map<String, String> extractHeaders(JsonNode headerNode) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!headerNode.isArray()) {
            return headers;
        }

        for (JsonNode header : headerNode) {
            String name = header.path("name").asText(null);
            String value = header.path("value").asText("");
            if (name == null || name.isBlank() || RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            headers.put(name, value);
        }
        return headers;
    }

    private Map<String, String> extractQueryParams(JsonNode queryNode) {
        Map<String, String> params = new LinkedHashMap<>();
        if (!queryNode.isArray()) {
            return params;
        }
        for (JsonNode param : queryNode) {
            String name = param.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            params.put(name, param.path("value").asText(""));
        }
        return params;
    }

    private String extractBody(JsonNode postData) {
        if (postData == null || postData.isMissingNode() || postData.isNull()) {
            return null;
        }

        String text = postData.path("text").asText(null);
        if (text != null && !text.isBlank()) {
            return text;
        }

        JsonNode params = postData.path("params");
        if (!params.isArray()) {
            return null;
        }

        List<String> encoded = new ArrayList<>();
        for (JsonNode param : params) {
            String name = param.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            encoded.add(name + "=" + param.path("value").asText(""));
        }
        return String.join("&", encoded);
    }

    private String normalizePath(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return "/";
            }
            String[] segments = path.split("/");
            List<String> normalized = new ArrayList<>();
            for (String segment : segments) {
                if (segment == null || segment.isBlank()) {
                    continue;
                }
                if (segment.matches("\\d+")) {
                    normalized.add("{id}");
                } else if (segment.matches("[0-9a-fA-F]{8,}")) {
                    normalized.add("{id}");
                } else {
                    normalized.add(segment);
                }
            }
            return "/" + String.join("/", normalized);
        } catch (Exception e) {
            String path = url;
            int queryIndex = path.indexOf('?');
            if (queryIndex >= 0) {
                path = path.substring(0, queryIndex);
            }
            return path.isBlank() ? "/" : path;
        }
    }

    private String extractBaseUrl(String requestUrl) {
        try {
            URI uri = URI.create(requestUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return "http://localhost:8080";
            }
            int port = uri.getPort();
            return port > -1 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
        } catch (Exception e) {
            return "http://localhost:8080";
        }
    }
}

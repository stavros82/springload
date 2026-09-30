package com.springload.strategy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springload.dto.ExecutionSettings;
import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.Thresholds;
import com.springload.util.correlation.ExtractorPathBuilder;
import com.springload.util.correlation.HarCorrelationScanner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            List<HarCorrelationScanner.HarEntry> harEntries = new ArrayList<>();
            List<ScenarioConfig> scenarios = new ArrayList<>();
            String baseUrl = null;
            String harName = "Imported HAR";
            boolean sawEntriesArray = false;

            try (JsonParser parser = OBJECT_MAPPER.getFactory().createParser(inputStream)) {
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token != JsonToken.FIELD_NAME || !"entries".equals(parser.currentName())) {
                        continue;
                    }
                    if (parser.nextToken() != JsonToken.START_ARRAY) {
                        throw new IllegalArgumentException("Invalid HAR file: missing 'log.entries' array.");
                    }
                    sawEntriesArray = true;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        JsonNode entry = OBJECT_MAPPER.readTree(parser);
                        ParsedHarRow row = readRow(entry);
                        if (row == null) {
                            continue;
                        }
                        if (baseUrl == null) {
                            baseUrl = extractBaseUrl(row.requestUrl());
                        }
                        harEntries.add(row.harEntry());
                        scenarios.add(row.scenario());
                    }
                    break;
                }
            }

            if (!sawEntriesArray) {
                throw new IllegalArgumentException("Invalid HAR file: missing 'log.entries' array.");
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
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse HAR file: " + e.getMessage(), e);
        }
    }

    private ParsedHarRow readRow(JsonNode entry) {
        if (entry == null || entry.isNull()) {
            return null;
        }
        JsonNode requestNode = entry.path("request");
        if (requestNode.isMissingNode()) {
            return null;
        }

        String requestUrl = requestNode.path("url").asText(null);
        if (requestUrl == null || requestUrl.isBlank()) {
            return null;
        }
        if (HarCorrelationScanner.isStaticAssetPath(requestUrl)) {
            return null;
        }

        String method = requestNode.path("method").asText("GET").toUpperCase(Locale.ROOT);
        String path = normalizePath(requestUrl);
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
        ScenarioConfig scenario = new ScenarioConfig(
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
        );
        return new ParsedHarRow(requestUrl, harEntry, scenario);
    }

    private record ParsedHarRow(
            String requestUrl,
            HarCorrelationScanner.HarEntry harEntry,
            ScenarioConfig scenario) {
    }

    private List<ScenarioConfig> applyCorrelationRules(List<ScenarioConfig> scenarios, List<HarCorrelationScanner.HarEntry> harEntries) {
        if (scenarios == null || scenarios.isEmpty() || harEntries == null || harEntries.isEmpty()) {
            return scenarios;
        }

        List<HarCorrelationScanner.CorrelationMatch> matches = HarCorrelationScanner.scan(harEntries);
        List<ScenarioConfig> resolved = new ArrayList<>(scenarios);

        for (HarCorrelationScanner.CorrelationMatch match : matches) {
            Integer upstreamIndex = resolveMatchIndex(match.sourceIndex(), harEntries.size());
            Integer downstreamIndex = resolveMatchIndex(match.downstreamIndex(), harEntries.size());
            if (upstreamIndex == null || downstreamIndex == null) {
                continue;
            }

            ScenarioConfig upstream = resolved.get(upstreamIndex);
            ScenarioConfig downstream = resolved.get(downstreamIndex);
            String variableName = inferVariableName(match.parameterName(), match.parameterValue(), match.value());

            String selector = buildExtractionSelector(harEntries.get(upstreamIndex), match.value());
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

    private Integer resolveMatchIndex(int index, int size) {
        if (index < 0 || index >= size) {
            return null;
        }
        return index;
    }

    private String buildExtractionSelector(HarCorrelationScanner.HarEntry entry, String value) {
        if (entry == null) {
            return ExtractorPathBuilder.buildRegexFallback(value);
        }
        String jsonPath = ExtractorPathBuilder.buildJsonPath(entry.responseBody(), value);
        if (jsonPath != null && !jsonPath.isBlank()) {
            return jsonPath;
        }
        if (entry.responseHeaders() != null) {
            for (Map.Entry<String, List<String>> responseHeader : entry.responseHeaders().entrySet()) {
                for (String headerValue : responseHeader.getValue()) {
                    if (value.equals(headerValue)) {
                        return "header:" + responseHeader.getKey();
                    }
                }
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

        if (canSafelyReplace(matchedValue)) {
            if (body != null && body.contains(matchedValue)) {
                body = body.replace(matchedValue, "${" + variableName + "}");
            }
            if (path != null) {
                String updatedPath = replacePathPlaceholder(path, parameterName, variableName, matchedValue);
                if (updatedPath != null) {
                    path = updatedPath;
                }
            }
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

    private String replacePathPlaceholder(String path, String parameterName, String variableName, String matchedValue) {
        if (path == null || path.isBlank()) {
            return path;
        }
        if (matchedValue != null && !matchedValue.isBlank() && path.contains(matchedValue)) {
            return path.replace(matchedValue, "${" + variableName + "}");
        }

        Set<String> candidateNames = new LinkedHashSet<>();
        if (parameterName != null && !parameterName.isBlank()) {
            candidateNames.add(parameterName.trim());
        }
        if (variableName != null && !variableName.isBlank()) {
            candidateNames.add(variableName.trim());
        }
        Pattern placeholderPattern = Pattern.compile("(?<!\\$)\\{([A-Za-z_$][A-Za-z0-9_$-]*)}");
        Matcher matcher = placeholderPattern.matcher(path);
        StringBuffer updated = new StringBuffer();
        boolean replaced = false;
        while (matcher.find()) {
            String placeholderName = matcher.group(1);
            if (candidateNames.stream().anyMatch(candidate -> samePlaceholderName(candidate, placeholderName))) {
                matcher.appendReplacement(updated, Matcher.quoteReplacement("${" + variableName + "}"));
                replaced = true;
            } else if (!replaced && !candidateNames.isEmpty()) {
                matcher.appendReplacement(updated, Matcher.quoteReplacement("${" + variableName + "}"));
                replaced = true;
            } else {
                matcher.appendReplacement(updated, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(updated);
        return replaced ? updated.toString() : path;
    }

    private boolean samePlaceholderName(String candidate, String placeholderName) {
        if (candidate == null || placeholderName == null) {
            return false;
        }
        String normalizedCandidate = candidate.trim();
        String normalizedPlaceholder = placeholderName.trim();
        if (normalizedCandidate.equalsIgnoreCase(normalizedPlaceholder)) {
            return true;
        }
        return normalizedCandidate.replaceAll("[^A-Za-z0-9_$-]", "_")
                .equalsIgnoreCase(normalizedPlaceholder.replaceAll("[^A-Za-z0-9_$-]", "_"));
    }

    private boolean canSafelyReplace(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.length() <= 256 && value.matches("[A-Za-z0-9_:/.-]{1,256}");
    }

    private String extractResponseBody(JsonNode responseNode) {
        if (responseNode == null || responseNode.isMissingNode() || responseNode.isNull()) {
            return null;
        }
        JsonNode content = responseNode.path("content");
        String text = content.path("text").asText(null);
        return HarCorrelationScanner.capBody(text);
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

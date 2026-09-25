package com.springload.util.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HarCorrelationScanner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern STATIC_ASSET_PATTERN = Pattern.compile(
            "(?i)(?:^|/)[^/?#]+\\.(?:css|js|mjs|cjs|png|jpe?g|gif|svg|webp|avif|ico|woff2?|ttf|otf|eot|map)(?:\\?.*)?$");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{6,}");
    private static final Set<String> THIRD_PARTY_HOSTS = Set.of(
            "google-analytics.com",
            "googletagmanager.com",
            "doubleclick.net",
            "cdn.jsdelivr.net",
            "cdnjs.cloudflare.com",
            "gstatic.com",
            "googleusercontent.com",
            "fonts.gstatic.com",
            "fontawesome.com");

    private HarCorrelationScanner() {
    }

    public record HarEntry(
            String requestUrl,
            Map<String, String> requestParameters,
            Map<String, String> requestHeaders,
            String requestBody,
            String responseBody,
            Map<String, List<String>> responseHeaders) {
        public HarEntry {
            requestParameters = requestParameters == null ? Map.of() : Map.copyOf(requestParameters);
            requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
            responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
        }
    }

    public record CorrelationMatch(String value, String requestUrl, String parameterName, String parameterValue) {
    }

    public static List<HarEntry> filterStaticAssetEntries(List<HarEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .filter(entry -> entry != null)
                .filter(entry -> !isStaticAssetPath(entry.requestUrl()))
                .toList();
    }

    public static List<HarEntry> filterStaticAssetPaths(List<HarEntry> entries) {
        return filterStaticAssetEntries(entries);
    }

    public static List<HarEntry> excludeStaticAssets(List<HarEntry> entries) {
        return filterStaticAssetEntries(entries);
    }

    public static List<CorrelationMatch> scan(List<HarEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<HarEntry> filteredEntries = filterStaticAssetEntries(entries);
        Set<CorrelationMatch> matches = new LinkedHashSet<>();
        for (int i = 0; i < filteredEntries.size(); i++) {
            HarEntry source = filteredEntries.get(i);
            if (source == null || source.responseBody() == null || source.responseBody().isBlank()) {
                continue;
            }
            for (String sourceValue : extractCandidateValues(source.responseBody(), source.responseHeaders())) {
                if (sourceValue == null || sourceValue.length() < 6) {
                    continue;
                }
                if (UniquenessScorer.score(sourceValue) != UniquenessScorer.UniquenessLevel.HIGH) {
                    continue;
                }
                for (int j = i + 1; j < filteredEntries.size(); j++) {
                    HarEntry downstream = filteredEntries.get(j);
                    if (downstream == null) {
                        continue;
                    }
                    for (Map.Entry<String, String> parameter : collectRequestParameters(downstream).entrySet()) {
                        String downstreamValue = parameter.getValue();
                        if (downstreamValue == null || downstreamValue.isBlank()) {
                            continue;
                        }
                        String normalizedSource = normalizeCandidate(sourceValue);
                        String normalizedDownstream = normalizeCandidate(downstreamValue);
                        if (normalizedSource.equals(normalizedDownstream)
                                || normalizedDownstream.contains(normalizedSource)
                                || normalizedSource.contains(normalizedDownstream)) {
                            matches.add(new CorrelationMatch(sourceValue, downstream.requestUrl(), parameter.getKey(), downstreamValue));
                        }
                    }
                }
            }
        }
        return List.copyOf(matches);
    }

    public static List<CorrelationMatch> findMatches(List<HarEntry> entries) {
        return scan(entries);
    }

    public static List<CorrelationMatch> detectCorrelationMatches(List<HarEntry> entries) {
        return scan(entries);
    }

    public static List<CorrelationMatch> findCorrelationMatches(List<HarEntry> entries) {
        return scan(entries);
    }

    public static boolean isStaticAssetPath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String path = value;
        try {
            URI uri = URI.create(value);
            if (uri.getPath() != null && !uri.getPath().isBlank()) {
                path = uri.getPath();
            }
        } catch (Exception ignored) {
            // Ignore malformed URLs and fall back to the raw input.
        }

        if (STATIC_ASSET_PATTERN.matcher(path).find()) {
            return true;
        }

        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                URI uri = URI.create(value);
                String host = uri.getHost();
                if (host != null) {
                    String normalized = host.toLowerCase();
                    for (String externalHost : THIRD_PARTY_HOSTS) {
                        if (normalized.contains(externalHost) || normalized.endsWith("." + externalHost)) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Ignore parse errors.
            }
        }
        return false;
    }

    private static Map<String, String> collectRequestParameters(HarEntry entry) {
        Map<String, String> values = new LinkedHashMap<>();
        if (entry == null) {
            return values;
        }
        if (entry.requestParameters() != null) {
            values.putAll(entry.requestParameters());
        }
        if (entry.requestHeaders() != null) {
            values.putAll(entry.requestHeaders());
        }
        if (entry.requestBody() != null && !entry.requestBody().isBlank()) {
            values.putAll(parseStructuredValues(entry.requestBody()));
        }
        return values;
    }

    private static Map<String, String> parseStructuredValues(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = JSON.readTree(body);
            Map<String, String> values = new LinkedHashMap<>();
            collectJsonValues(root, values, "");
            return values;
        } catch (Exception ignored) {
            Map<String, String> values = new LinkedHashMap<>();
            Matcher matcher = TOKEN_PATTERN.matcher(body);
            while (matcher.find()) {
                String token = matcher.group();
                values.put(token, token);
            }
            return values;
        }
    }

    private static void collectJsonValues(JsonNode node, Map<String, String> values, String path) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String nestedPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                collectJsonValues(entry.getValue(), values, nestedPath);
            });
            return;
        }
        if (node.isArray()) {
            int index = 0;
            for (JsonNode child : node) {
                String nestedPath = path.isEmpty() ? String.valueOf(index) : path + "." + index;
                collectJsonValues(child, values, nestedPath);
                index++;
            }
            return;
        }
        if (node.isValueNode() && !node.isNull()) {
            String text = node.asText();
            if (!text.isBlank()) {
                values.put(path.isEmpty() ? text : path, text);
            }
        }
    }

    private static List<String> extractCandidateValues(String responseBody, Map<String, List<String>> responseHeaders) {
        List<String> values = new ArrayList<>();
        if (responseBody != null && !responseBody.isBlank()) {
            values.addAll(extractFromText(responseBody));
            try {
                JsonNode root = JSON.readTree(responseBody);
                collectStringValues(root, values);
            } catch (Exception ignored) {
                // Non-JSON payloads are still handled by regex extraction above.
            }
        }
        if (responseHeaders != null) {
            for (List<String> headerValues : responseHeaders.values()) {
                if (headerValues != null) {
                    for (String headerValue : headerValues) {
                        if (headerValue != null && !headerValue.isBlank()) {
                            values.add(headerValue);
                        }
                    }
                }
            }
        }
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private static void collectStringValues(JsonNode node, List<String> values) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectStringValues(entry.getValue(), values));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectStringValues(child, values));
            return;
        }
        if (node.isValueNode() && !node.isNull()) {
            String text = node.asText();
            if (!text.isBlank()) {
                values.add(text);
            }
        }
    }

    private static List<String> extractFromText(String body) {
        List<String> values = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return values;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(body);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 6) {
                values.add(token);
            }
        }
        return values;
    }

    private static String normalizeCandidate(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("[\r\n\t\\\"]", "");
    }
}

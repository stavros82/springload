package com.springload.util.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

public final class HarCorrelationScanner {
    static final int MAX_CANDIDATE_LENGTH = 512;
    static final int MAX_BODY_CHARS = 256 * 1024;
    private static final int MAX_CORRELATION_MATCHES = 256;
    private static final int MAX_TOKEN_MATCHES = 64;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern STATIC_ASSET_PATTERN = Pattern.compile(
            "(?i)(?:^|/)[^/?#]+\\.(?:css|js|mjs|cjs|png|jpe?g|gif|svg|webp|avif|ico|woff2?|ttf|otf|eot|map)(?:\\?.*)?$");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_.:/-]{6,}");
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\\d{4,18}");
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
    private static final Set<String> NON_CORRELATED_HEADERS = Set.of(
            "accept",
            "accept-language",
            "accept-encoding",
            "user-agent",
            "content-type",
            "content-length",
            "connection",
            "origin",
            "referer",
            "sec-fetch-mode",
            "sec-fetch-site",
            "sec-fetch-dest",
            "sec-fetch-user",
            "sec-ch-ua",
            "sec-ch-ua-mobile",
            "sec-ch-ua-platform",
            "upgrade-insecure-requests",
            "cache-control",
            "pragma",
            "host");

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
            requestBody = capBody(requestBody);
            responseBody = capBody(responseBody);
        }
    }

    public record CorrelationMatch(
            String value,
            String requestUrl,
            String parameterName,
            String parameterValue,
            int sourceIndex,
            int downstreamIndex) {
        public CorrelationMatch(String value, String requestUrl, String parameterName, String parameterValue) {
            this(value, requestUrl, parameterName, parameterValue, -1, -1);
        }
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
        List<Map<String, String>> requestValues = new ArrayList<>(filteredEntries.size());
        Map<String, List<RequestOccurrence>> occurrencesByValue = new LinkedHashMap<>();

        for (int i = 0; i < filteredEntries.size(); i++) {
            Map<String, String> parameters = collectRequestParameters(filteredEntries.get(i));
            requestValues.add(parameters);
            for (Map.Entry<String, String> parameter : parameters.entrySet()) {
                String value = parameter.getValue();
                if (!isCorrelatableRequestValue(value)) {
                    continue;
                }
                String normalized = normalizeCandidate(value);
                occurrencesByValue
                        .computeIfAbsent(normalized, ignored -> new ArrayList<>())
                        .add(new RequestOccurrence(i, parameter.getKey(), value));
            }
        }

        if (occurrencesByValue.isEmpty()) {
            return List.of();
        }

        Set<CorrelationMatch> matches = new LinkedHashSet<>();
        for (int i = 0; i < filteredEntries.size() && matches.size() < MAX_CORRELATION_MATCHES; i++) {
            HarEntry source = filteredEntries.get(i);
            if (source == null) {
                continue;
            }
            for (Map.Entry<String, List<RequestOccurrence>> occurrenceEntry : occurrencesByValue.entrySet()) {
                if (matches.size() >= MAX_CORRELATION_MATCHES) {
                    break;
                }
                String normalizedValue = occurrenceEntry.getKey();
                if (!sourceContainsValue(source, normalizedValue)) {
                    continue;
                }
                boolean highUniqueness = UniquenessScorer.score(normalizedValue) == UniquenessScorer.UniquenessLevel.HIGH;
                for (RequestOccurrence occurrence : occurrenceEntry.getValue()) {
                    if (occurrence.index() <= i) {
                        continue;
                    }
                    if (!highUniqueness && !hasNumericBoundary(source.responseBody(), normalizedValue)) {
                        continue;
                    }
                    matches.add(new CorrelationMatch(
                            occurrence.value(),
                            filteredEntries.get(occurrence.index()).requestUrl(),
                            occurrence.parameterName(),
                            occurrence.value(),
                            i,
                            occurrence.index()));
                    if (matches.size() >= MAX_CORRELATION_MATCHES) {
                        break;
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
                    String normalized = host.toLowerCase(Locale.ROOT);
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

    private static boolean sourceContainsValue(HarEntry source, String value) {
        if (source == null || value == null || value.isBlank()) {
            return false;
        }
        if (source.responseHeaders() != null) {
            for (List<String> headerValues : source.responseHeaders().values()) {
                if (headerValues == null) {
                    continue;
                }
                for (String headerValue : headerValues) {
                    if (value.equals(normalizeCandidate(headerValue))) {
                        return true;
                    }
                }
            }
        }
        String body = source.responseBody();
        return body != null && !body.isBlank() && body.contains(value);
    }

    private static boolean hasNumericBoundary(String body, String value) {
        if (body == null || value == null || value.isBlank()) {
            return false;
        }
        int fromIndex = 0;
        while (fromIndex < body.length()) {
            int index = body.indexOf(value, fromIndex);
            if (index < 0) {
                return false;
            }
            boolean leftOk = index == 0 || !Character.isDigit(body.charAt(index - 1));
            int end = index + value.length();
            boolean rightOk = end >= body.length() || !Character.isDigit(body.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            fromIndex = index + value.length();
        }
        return false;
    }

    private static boolean isCorrelatableRequestValue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = normalizeCandidate(value);
        if (normalized.length() > MAX_CANDIDATE_LENGTH) {
            return false;
        }
        if (NUMERIC_ID_PATTERN.matcher(normalized).matches()) {
            return true;
        }
        if (normalized.length() < 6) {
            return false;
        }
        return UniquenessScorer.score(normalized) == UniquenessScorer.UniquenessLevel.HIGH;
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
            for (Map.Entry<String, String> header : entry.requestHeaders().entrySet()) {
                if (header.getKey() != null && NON_CORRELATED_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                values.put(header.getKey(), header.getValue());
            }
        }
        addUrlBoundValues(entry.requestUrl(), values);
        if (entry.requestBody() != null && !entry.requestBody().isBlank()) {
            values.putAll(parseStructuredValues(entry.requestBody()));
        }
        return values;
    }

    private static void addUrlBoundValues(String requestUrl, Map<String, String> values) {
        if (requestUrl == null || requestUrl.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(requestUrl);
            String path = uri.getPath();
            if (path != null) {
                for (String segment : path.split("/")) {
                    if (segment == null || segment.isBlank()) {
                        continue;
                    }
                    if (isCorrelatableRequestValue(segment)) {
                        values.putIfAbsent("path:" + segment, segment);
                    }
                }
            }
        } catch (Exception ignored) {
            // Ignore malformed URLs.
        }
    }

    private static Map<String, String> parseStructuredValues(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        String bounded = capBody(body);
        if (bounded == null || bounded.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = JSON.readTree(bounded);
            Map<String, String> values = new LinkedHashMap<>();
            collectJsonValues(root, values, "");
            return values;
        } catch (Exception ignored) {
            Map<String, String> values = new LinkedHashMap<>();
            Matcher matcher = TOKEN_PATTERN.matcher(bounded);
            int matches = 0;
            while (matcher.find() && matches < MAX_TOKEN_MATCHES) {
                String token = matcher.group();
                values.put(token, token);
                matches++;
            }
            return values;
        }
    }

    private static void collectJsonValues(JsonNode node, Map<String, String> values, String path) {
        if (node == null || values.size() >= MAX_TOKEN_MATCHES) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (values.size() >= MAX_TOKEN_MATCHES) {
                    return;
                }
                String nestedPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                collectJsonValues(entry.getValue(), values, nestedPath);
            });
            return;
        }
        if (node.isArray()) {
            int index = 0;
            for (JsonNode child : node) {
                if (values.size() >= MAX_TOKEN_MATCHES) {
                    return;
                }
                String nestedPath = path.isEmpty() ? String.valueOf(index) : path + "." + index;
                collectJsonValues(child, values, nestedPath);
                index++;
            }
            return;
        }
        if (node.isValueNode() && !node.isNull()) {
            String text = node.asText();
            if (!text.isBlank() && text.length() <= MAX_CANDIDATE_LENGTH) {
                values.put(path.isEmpty() ? text : path, text);
            }
        }
    }

    public static String capBody(String body) {
        if (body == null) {
            return null;
        }
        if (body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        return null;
    }

    public static String normalizeCandidate(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("[\r\n\t\\\\\"]", "");
    }

    private record RequestOccurrence(int index, String parameterName, String value) {
    }
}

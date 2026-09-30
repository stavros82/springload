package com.springload.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpHeaders;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe, lock-free resolver for dynamic placeholder expressions in URL paths,
 * headers, and JSON body templates. Supports both {@code ${name}} and {@code {name}}
 * flow-variable references.
 */
public final class DynamicVariableResolver {

    private static final String PLACEHOLDER_PREFIX = "${";
    private static final int MAX_RESOLUTION_DEPTH = 12;
    private static final Pattern RANDOM_RANGE = Pattern.compile("\\$\\{random\\((\\d+)-(\\d+)\\)}");
    private static final Pattern RANDOM_UUID = Pattern.compile("\\$\\{random\\.uuid}");
    private static final Pattern TIMESTAMP = Pattern.compile("\\$\\{timestamp}");
    private static final Pattern UNRESOLVED = Pattern.compile("\\$\\{[^}]+}");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern PATH_VARIABLE = Pattern.compile("(?<!\\$)\\{([A-Za-z_$][A-Za-z0-9_$-]*)}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private DynamicVariableResolver() {}

    /**
     * Returns true if the template contains correlated variable placeholders that cannot
     * be resolved statically (i.e. anything other than random.uuid, timestamp, random(min-max)).
     * Such scenarios depend on prior response extraction and must be skipped in stateless mode.
     */
    public static boolean hasCorrelatedVariables(String template) {
        if (template == null || (template.indexOf(PLACEHOLDER_PREFIX) < 0 && template.indexOf('{') < 0)) {
            return false;
        }
        String stripped = RANDOM_RANGE.matcher(template).replaceAll("");
        stripped = RANDOM_UUID.matcher(stripped).replaceAll("");
        stripped = TIMESTAMP.matcher(stripped).replaceAll("");
        return UNRESOLVED.matcher(stripped).find() || PATH_VARIABLE.matcher(stripped).find();
    }

    public static String resolve(String template) {
        return resolve(template, Map.of());
    }

    public static String resolve(String template, Map<String, String> variables) {
        if (template == null || (!template.contains(PLACEHOLDER_PREFIX) && !PATH_VARIABLE.matcher(template).find())) {
            return template;
        }

        String current = template;
        for (int depth = 0; depth < MAX_RESOLUTION_DEPTH; depth++) {
            String previous = current;
            current = replaceRandomRanges(current, matcher -> {
                int min = Integer.parseInt(matcher.group(1));
                int max = Integer.parseInt(matcher.group(2));
                if (min > max) {
                    int tmp = min;
                    min = max;
                    max = tmp;
                }
                return String.valueOf(ThreadLocalRandom.current().nextInt(min, max + 1));
            });

            current = RANDOM_UUID.matcher(current).replaceAll(match -> UUID.randomUUID().toString());
            current = TIMESTAMP.matcher(current).replaceAll(match -> String.valueOf(System.currentTimeMillis()));
            current = replaceVariables(current, variables);
            current = replacePathVariables(current, variables);

            if (current.equals(previous)) {
                break;
            }
        }
        return current;
    }

    private static String replaceVariables(String input, Map<String, String> variables) {
        if (input == null || input.isEmpty() || variables == null || variables.isEmpty()) {
            return input;
        }

        StringBuilder resolved = new StringBuilder();
        int cursor = 0;
        while (cursor < input.length()) {
            int dollarIndex = input.indexOf("${", cursor);
            int braceIndex = input.indexOf("{", cursor);
            int nextIndex = nextPlaceholderStart(dollarIndex, braceIndex);
            if (nextIndex < 0) {
                resolved.append(input.substring(cursor));
                break;
            }
            resolved.append(input, cursor, nextIndex);

            boolean isDollarPlaceholder = dollarIndex == nextIndex;
            int closingIndex = findClosingBrace(input, nextIndex + (isDollarPlaceholder ? 2 : 1));
            if (closingIndex < 0) {
                resolved.append(input.substring(nextIndex));
                break;
            }

            String placeholderBody = input.substring(nextIndex + (isDollarPlaceholder ? 2 : 1), closingIndex);
            String replacement = resolvePlaceholderValue(placeholderBody, variables);
            if (replacement == null) {
                resolved.append(input, nextIndex, closingIndex + 1);
            } else {
                resolved.append(replacement);
            }
            cursor = closingIndex + 1;
        }
        return resolved.toString();
    }

    private static String replacePathVariables(String input, Map<String, String> variables) {
        if (input == null || input.isEmpty() || variables == null || variables.isEmpty()) {
            return input;
        }
        StringBuilder resolved = new StringBuilder();
        int cursor = 0;
        while (cursor < input.length()) {
            int openIndex = input.indexOf('{', cursor);
            if (openIndex < 0) {
                resolved.append(input.substring(cursor));
                break;
            }
            if (openIndex > 0 && input.charAt(openIndex - 1) == '$') {
                resolved.append(input, cursor, openIndex + 1);
                cursor = openIndex + 1;
                continue;
            }
            int closingIndex = findClosingBrace(input, openIndex + 1);
            if (closingIndex < 0) {
                resolved.append(input.substring(cursor));
                break;
            }
            String name = input.substring(openIndex + 1, closingIndex);
            String replacement = variables.getOrDefault(name, null);
            if (replacement == null) {
                resolved.append(input, cursor, closingIndex + 1);
            } else {
                resolved.append(input, cursor, openIndex).append(replacement);
            }
            cursor = closingIndex + 1;
        }
        return resolved.toString();
    }

    private static String resolvePlaceholderValue(String placeholderBody, Map<String, String> variables) {
        if (placeholderBody == null) {
            return null;
        }
        String trimmed = placeholderBody.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String indirectName = unwrapReferenceName(trimmed);
        if (indirectName != null && variables.containsKey(indirectName)) {
            return variables.get(indirectName);
        }

        if (variables.containsKey(trimmed)) {
            return variables.get(trimmed);
        }

        return null;
    }

    private static String unwrapReferenceName(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return null;
        }
        while ((trimmed.startsWith("${") && trimmed.endsWith("}")) || (trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            String inner = trimmed.substring(trimmed.startsWith("${") ? 2 : 1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) {
                return null;
            }
            trimmed = inner;
        }
        return trimmed;
    }

    private static int nextPlaceholderStart(int dollarIndex, int braceIndex) {
        if (dollarIndex < 0 && braceIndex < 0) {
            return -1;
        }
        if (dollarIndex < 0) {
            return braceIndex;
        }
        if (braceIndex < 0) {
            return dollarIndex;
        }
        return Math.min(dollarIndex, braceIndex);
    }

    private static int findClosingBrace(String input, int startIndex) {
        if (input == null || startIndex < 0 || startIndex >= input.length()) {
            return -1;
        }
        int depth = 1;
        for (int i = startIndex; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static boolean hasUnresolvedVariables(String template) {
        return template != null && (UNRESOLVED.matcher(template).find() || PATH_VARIABLE.matcher(template).find());
    }

    public static Map<String, String> resolveHeaders(Map<String, String> headers) {
        return resolveHeaders(headers, Map.of());
    }

    public static Map<String, String> resolveHeaders(Map<String, String> headers, Map<String, String> variables) {
        if (headers == null || headers.isEmpty()) {
            return headers;
        }
        boolean needsResolution = headers.values().stream()
                .anyMatch(v -> v != null && (v.contains(PLACEHOLDER_PREFIX) || PATH_VARIABLE.matcher(v).find()));
        if (!needsResolution) {
            return headers;
        }
        return headers.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> resolve(e.getValue(), variables),
                        (a, b) -> b,
                        java.util.LinkedHashMap::new
                ));
    }

    public static String appendQueryParams(String uri, Map<String, String> queryParams,
                                           Map<String, String> variables) {
        if (queryParams == null || queryParams.isEmpty()) {
            return uri;
        }
        String separator = uri.contains("?") ? "&" : "?";
        StringBuilder result = new StringBuilder(uri);
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            result.append(separator)
                    .append(encode(resolve(entry.getKey(), variables)))
                    .append('=')
                    .append(encode(resolve(entry.getValue(), variables)));
            separator = "&";
        }
        return result.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public static Map<String, String> extract(
            Map<String, String> extractions, String responseBody, Map<String, List<String>> responseHeaders) {
        Map<String, String> values = new LinkedHashMap<>();
        if (extractions == null) {
            return values;
        }
        JsonNode json = null;
        for (Map.Entry<String, String> extraction : extractions.entrySet()) {
            String selector = extraction.getValue();
            if (selector == null) {
                continue;
            }
            if (selector.startsWith("header:")) {
                String headerName = selector.substring("header:".length());
                responseHeaders.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(headerName))
                        .findFirst()
                        .flatMap(entry -> entry.getValue().stream().findFirst())
                        .ifPresent(value -> values.put(extraction.getKey(), value));
                continue;
            }
            if (responseBody == null || responseBody.isBlank() || !selector.startsWith("$")) {
                continue;
            }
            try {
                if (json == null) {
                    json = JSON.readTree(responseBody);
                }
                JsonNode value = json;
                for (String segment : selector.substring(1).split("\\.")) {
                    if (!segment.isEmpty()) {
                        value = value.path(segment);
                    }
                }
                if (!value.isMissingNode() && !value.isNull()) {
                    values.put(extraction.getKey(),
                            value.isValueNode() ? value.asText() : value.toString());
                }
            } catch (Exception ignored) {
                // A response that does not match an extraction must not corrupt flow state.
            }
        }
        return values;
    }

    public static Map<String, String> extract(
            Map<String, String> extractions, String responseBody, HttpHeaders responseHeaders) {
        return extract(extractions, responseBody, responseHeaders.map());
    }

    private static String replaceRandomRanges(String input, Function<Matcher, String> replacer) {
        Matcher matcher = RANDOM_RANGE.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        StringBuilder sb = new StringBuilder();
        matcher.reset();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(matcher)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

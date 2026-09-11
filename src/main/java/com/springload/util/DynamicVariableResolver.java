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
        if (template == null || !template.contains(PLACEHOLDER_PREFIX)) {
            return false;
        }
        String stripped = RANDOM_RANGE.matcher(template).replaceAll("");
        stripped = RANDOM_UUID.matcher(stripped).replaceAll("");
        stripped = TIMESTAMP.matcher(stripped).replaceAll("");
        return UNRESOLVED.matcher(stripped).find();
    }

    public static String resolve(String template) {
        return resolve(template, Map.of());
    }

    public static String resolve(String template, Map<String, String> variables) {
        if (template == null || (!template.contains(PLACEHOLDER_PREFIX) && !PATH_VARIABLE.matcher(template).find())) {
            return template;
        }

        String result = replaceRandomRanges(template, matcher -> {
            int min = Integer.parseInt(matcher.group(1));
            int max = Integer.parseInt(matcher.group(2));
            if (min > max) {
                int tmp = min;
                min = max;
                max = tmp;
            }
            return String.valueOf(ThreadLocalRandom.current().nextInt(min, max + 1));
        });

        result = RANDOM_UUID.matcher(result).replaceAll(match -> UUID.randomUUID().toString());
        result = TIMESTAMP.matcher(result).replaceAll(match -> String.valueOf(System.currentTimeMillis()));
        Matcher variableMatcher = VARIABLE.matcher(result);
        StringBuffer resolved = new StringBuffer();
        while (variableMatcher.find()) {
            String value = variables.get(variableMatcher.group(1));
            variableMatcher.appendReplacement(resolved,
                    value == null ? Matcher.quoteReplacement(variableMatcher.group()) : Matcher.quoteReplacement(value));
        }
        variableMatcher.appendTail(resolved);
        Matcher pathVariableMatcher = PATH_VARIABLE.matcher(resolved);
        StringBuffer pathResolved = new StringBuffer();
        while (pathVariableMatcher.find()) {
            String value = variables.get(pathVariableMatcher.group(1));
            pathVariableMatcher.appendReplacement(pathResolved,
                    value == null ? Matcher.quoteReplacement(pathVariableMatcher.group()) : Matcher.quoteReplacement(value));
        }
        pathVariableMatcher.appendTail(pathResolved);
        return pathResolved.toString();
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

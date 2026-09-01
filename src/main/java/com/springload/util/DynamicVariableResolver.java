package com.springload.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe, lock-free resolver for dynamic placeholder expressions in URL paths,
 * headers, and JSON body templates. Strings without a {@code ${} prefix bypass regex entirely.
 */
public final class DynamicVariableResolver {

    private static final String PLACEHOLDER_PREFIX = "${";
    private static final Pattern RANDOM_RANGE = Pattern.compile("\\$\\{random\\((\\d+)-(\\d+)\\)}");
    private static final Pattern RANDOM_UUID = Pattern.compile("\\$\\{random\\.uuid}");
    private static final Pattern TIMESTAMP = Pattern.compile("\\$\\{timestamp}");

    private DynamicVariableResolver() {}

    public static String resolve(String template) {
        if (template == null || !template.contains(PLACEHOLDER_PREFIX)) {
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
        return result;
    }

    public static Map<String, String> resolveHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return headers;
        }
        boolean needsResolution = headers.values().stream()
                .anyMatch(v -> v != null && v.contains(PLACEHOLDER_PREFIX));
        if (!needsResolution) {
            return headers;
        }
        return headers.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> resolve(e.getValue()),
                        (a, b) -> b,
                        java.util.LinkedHashMap::new
                ));
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

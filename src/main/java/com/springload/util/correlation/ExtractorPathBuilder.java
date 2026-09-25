package com.springload.util.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExtractorPathBuilder {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExtractorPathBuilder() {
    }

    public static String buildJsonPath(String responseBody, String candidateValue) {
        if (responseBody == null || responseBody.isBlank() || candidateValue == null || candidateValue.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(responseBody);
            List<String> matches = new ArrayList<>();
            collectJsonPaths(root, "$", candidateValue, matches);
            return matches.isEmpty() ? null : matches.getFirst();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String buildJsonSelector(String responseBody, String candidateValue) {
        return buildJsonPath(responseBody, candidateValue);
    }

    public static String buildHeaderPath(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return null;
        }
        return "header:" + headerName.trim();
    }

    public static String buildHeaderSelector(String headerName) {
        return buildHeaderPath(headerName);
    }

    public static String buildRegexFallback(String candidateValue) {
        if (candidateValue == null || candidateValue.isBlank()) {
            return null;
        }
        return "(?s).*" + Pattern.quote(candidateValue) + ".*";
    }

    public static String buildRegexPattern(String candidateValue) {
        return buildRegexFallback(candidateValue);
    }

    public static boolean validateExtraction(String responseBody, String path, String expectedValue) {
        if (responseBody == null || responseBody.isBlank() || path == null || path.isBlank()) {
            return false;
        }
        String normalizedValue = expectedValue == null ? null : expectedValue.trim();

        if (path.startsWith("header:")) {
            String headerName = path.substring("header:".length()).trim();
            if (headerName.isEmpty()) {
                return false;
            }
            String headerPattern = "(?im)^" + Pattern.quote(headerName) + "\\s*:\\s*(.*)$";
            Matcher matcher = Pattern.compile(headerPattern).matcher(responseBody);
            if (!matcher.find()) {
                return false;
            }
            String captured = matcher.group(1).trim();
            return normalizedValue == null || captured.equals(normalizedValue)
                    || captured.contains(normalizedValue == null ? "" : normalizedValue);
        }

        if (path.startsWith("$")) {
            try {
                JsonNode root = JSON.readTree(responseBody);
                String actual = resolveJsonPath(root, path);
                return actual != null && (normalizedValue == null || actual.equals(normalizedValue));
            } catch (Exception ignored) {
                return false;
            }
        }

        try {
            Matcher matcher = Pattern.compile(path, Pattern.DOTALL).matcher(responseBody);
            if (!matcher.find()) {
                return false;
            }
            String matched = matcher.groupCount() > 0 ? matcher.group(1) : matcher.group();
            return normalizedValue == null || matched.equals(normalizedValue)
                    || matched.contains(normalizedValue == null ? "" : normalizedValue);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void collectJsonPaths(JsonNode node, String currentPath, String candidateValue, List<String> results) {
        if (node == null || results.size() > 20) {
            return;
        }

        if (node.isValueNode() && !node.isNull()) {
            String text = node.asText();
            if (candidateValue.equals(text)) {
                results.add(currentPath);
            }
            return;
        }

        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String nextPath = currentPath.equals("$") ? "$." + entry.getKey() : currentPath + "." + entry.getKey();
                collectJsonPaths(entry.getValue(), nextPath, candidateValue, results);
            });
            return;
        }

        if (node.isArray()) {
            int index = 0;
            for (JsonNode child : node) {
                String nextPath = currentPath + "[" + index + "]";
                collectJsonPaths(child, nextPath, candidateValue, results);
                index++;
            }
        }
    }

    private static String resolveJsonPath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode current = root;
        String normalized = path.startsWith("$") ? path.substring(1) : path;
        String[] segments = normalized.split("\\.");
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            String key = segment;
            int arrayIndex = -1;
            int indexStart = key.indexOf('[');
            if (indexStart >= 0) {
                int indexEnd = key.indexOf(']', indexStart);
                if (indexEnd > indexStart) {
                    String numeric = key.substring(indexStart + 1, indexEnd);
                    if (numeric.matches("\\d+")) {
                        arrayIndex = Integer.parseInt(numeric);
                    }
                    key = key.substring(0, indexStart);
                }
            }
            if (!key.isBlank()) {
                current = current.path(key);
            }
            if (arrayIndex >= 0 && current != null && current.isArray()) {
                current = current.path(arrayIndex);
            }
            if (current == null || current.isMissingNode()) {
                return null;
            }
        }
        return current.isValueNode() ? current.asText() : current.toString();
    }
}

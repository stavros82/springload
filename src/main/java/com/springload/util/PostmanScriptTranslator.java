package com.springload.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates Postman JavaScript test scripts into declarative extract YAML definitions.
 * Scans for pm.environment.set, pm.globals.set, and pm.variables.set calls,
 * then translates expressions (JSON paths, headers) into our extract format.
 */
public class PostmanScriptTranslator {

    private static final Pattern SET_PATTERN = Pattern.compile(
        "pm\\.(environment|globals|variables)\\.set\\(\\s*[\"']([^\"']+)[\"']\\s*,\\s*(.+)\\s*\\);?\\s*$"
    );
    private static final Pattern JSON_ACCESS_PATTERN = Pattern.compile(
        "pm\\.response\\.json\\(\\)(?:\\.([a-zA-Z0-9_$.]+)|\\[[\"']([^\"']+)[\"']\\])?"
    );
    private static final Pattern HEADER_GET_PATTERN = Pattern.compile(
        "pm\\.response\\.headers\\.get\\(\\s*[\"']([^\"']+)[\"']\\s*\\)"
    );

    /**
     * Translates a list of script lines into a map of variable extractions.
     * @param scriptLines Individual lines from a Postman test script
     * @return Map of variable names to their extract expressions
     */
    public static Map<String, String> translate(List<String> scriptLines) {
        Map<String, String> extractions = new HashMap<>();
        if (scriptLines == null) {
            return extractions;
        }
        for (String line : scriptLines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("/*")) {
                continue;
            }
            Matcher setMatcher = SET_PATTERN.matcher(line);
            if (setMatcher.find()) {
                String varName = setMatcher.group(2);
                String valueExpression = setMatcher.group(3).trim();

                // Balance parentheses: if closing paren count exceeds opening, remove the last one
                if (valueExpression.endsWith(")")) {
                    long open = valueExpression.chars().filter(ch -> ch == '(').count();
                    long close = valueExpression.chars().filter(ch -> ch == ')').count();
                    if (close > open) {
                        valueExpression = valueExpression.substring(0, valueExpression.length() - 1).trim();
                    }
                }

                String translated = translateExpression(valueExpression);
                if (translated != null) {
                    extractions.put(varName, translated);
                }
            }
        }
        return extractions;
    }

    /**
     * Translates a Postman script expression into an extract selector.
     * - pm.response.json().path → $.path (JSONPath)
     * - pm.response.headers.get("X-Token") → header:X-Token
     *
     * @param expr The expression to translate
     * @return The extract selector, or null if not recognized
     */
    private static String translateExpression(String expr) {
        // Try JSON path first
        Matcher jsonMatcher = JSON_ACCESS_PATTERN.matcher(expr);
        if (jsonMatcher.find()) {
            String dotProps = jsonMatcher.group(1);
            String bracketProp = jsonMatcher.group(2);
            String props = dotProps != null ? dotProps : bracketProp;
            return (props == null || props.isEmpty()) ? "$" : "$." + props;
        }

        // Try header get
        Matcher headerMatcher = HEADER_GET_PATTERN.matcher(expr);
        if (headerMatcher.find()) {
            return "header:" + headerMatcher.group(1);
        }

        return null;
    }
}

package com.springload.util.correlation;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class UniquenessScorer {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern JWT_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    private UniquenessScorer() {
    }

    public enum UniquenessLevel {
        HIGH,
        LOW
    }

    public static UniquenessLevel score(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return UniquenessLevel.LOW;
        }
        String value = candidate.trim();
        if (UUID_PATTERN.matcher(value).matches()) {
            return UniquenessLevel.HIGH;
        }
        if (value.startsWith("eyJ") && JWT_PATTERN.matcher(value).matches()) {
            return UniquenessLevel.HIGH;
        }
        double entropy = calculateShannonEntropy(value);
        if (value.length() > 16 && entropy >= 3.2) {
            return UniquenessLevel.HIGH;
        }
        return UniquenessLevel.LOW;
    }

    public static UniquenessLevel evaluate(String candidate) {
        return score(candidate);
    }

    public static UniquenessLevel classify(String candidate) {
        return score(candidate);
    }

    public static UniquenessLevel evaluateCandidate(String candidate) {
        return score(candidate);
    }

    public static boolean isHighUniqueness(String candidate) {
        return score(candidate) == UniquenessLevel.HIGH;
    }

    public static boolean isHigh(String candidate) {
        return isHighUniqueness(candidate);
    }

    public static boolean isLowUniqueness(String candidate) {
        return score(candidate) == UniquenessLevel.LOW;
    }

    public static boolean isLow(String candidate) {
        return isLowUniqueness(candidate);
    }

    static double calculateShannonEntropy(String value) {
        if (value == null || value.isEmpty()) {
            return 0.0;
        }
        Map<Character, Integer> counts = new HashMap<>();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            counts.put(ch, counts.getOrDefault(ch, 0) + 1);
        }

        double entropy = 0.0;
        int length = value.length();
        for (int count : counts.values()) {
            double probability = (double) count / length;
            entropy -= probability * (Math.log(probability) / Math.log(2.0));
        }
        return entropy;
    }
}

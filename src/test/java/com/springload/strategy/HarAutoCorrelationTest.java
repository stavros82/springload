package com.springload.strategy;

import com.springload.util.correlation.ExtractorPathBuilder;
import com.springload.util.correlation.HarCorrelationScanner;
import com.springload.util.correlation.UniquenessScorer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HarAutoCorrelationTest {

    @Test
    void scoresUuidAndJwtAsHighUniqueness() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature";

        assertEquals(UniquenessScorer.UniquenessLevel.HIGH, UniquenessScorer.score(uuid));
        assertTrue(UniquenessScorer.isHighUniqueness(uuid));
        assertEquals(UniquenessScorer.UniquenessLevel.HIGH, UniquenessScorer.score(jwt));
        assertTrue(UniquenessScorer.isHighUniqueness(jwt));
    }

    @Test
    void localizesSimpleIdsToLowUniqueness() {
        assertEquals(UniquenessScorer.UniquenessLevel.LOW, UniquenessScorer.score("42"));
        assertEquals(UniquenessScorer.UniquenessLevel.LOW, UniquenessScorer.score("1"));
        assertFalse(UniquenessScorer.isHighUniqueness("42"));
    }

    @Test
    void filtersStaticAssetsAndFindsDownstreamCorrelation() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature";

        List<HarCorrelationScanner.HarEntry> entries = List.of(
                new HarCorrelationScanner.HarEntry(
                        "https://app.example.com/auth/login",
                        Map.of(),
                        Map.of(),
                        "{\"userId\":42,\"sessionId\":\"" + uuid + "\"}",
                        "{\"sessionId\":\"" + uuid + "\",\"token\":\"" + jwt + "\"}",
                        Map.of("X-Trace-Id", List.of(uuid))),
                new HarCorrelationScanner.HarEntry(
                        "https://app.example.com/api/orders?session=" + uuid,
                        Map.of("session", uuid),
                        Map.of("Authorization", jwt),
                        "{\"orderId\":42}",
                        "{\"success\":true}",
                        Map.of()),
                new HarCorrelationScanner.HarEntry(
                        "https://app.example.com/assets/app.js",
                        Map.of(),
                        Map.of(),
                        null,
                        "console.log('static');",
                        Map.of())
        );

        List<HarCorrelationScanner.CorrelationMatch> results = HarCorrelationScanner.scan(entries);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(match -> match.value().equals(uuid) || match.value().equals(jwt)));
        assertTrue(HarCorrelationScanner.isStaticAssetPath("https://app.example.com/assets/app.js"));
        assertFalse(HarCorrelationScanner.filterStaticAssetEntries(entries).stream()
                .anyMatch(entry -> entry.requestUrl().contains("/assets/app.js")));
    }

    @Test
    void buildsJsonPathAndHeaderSelectorsAndProofsExtraction() {
        String responseBody = "{\"data\":{\"session\":{\"token\":\"abc123.jwt.token\"}},\"headers\":{\"X-Auth-Token\":\"abc123.jwt.token\"}}";
        String token = "abc123.jwt.token";

        String jsonPath = ExtractorPathBuilder.buildJsonPath(responseBody, token);
        assertEquals("$.data.session.token", jsonPath);
        assertTrue(ExtractorPathBuilder.validateExtraction(responseBody, jsonPath, token));

        String headerPath = ExtractorPathBuilder.buildHeaderPath("X-Auth-Token");
        assertEquals("header:X-Auth-Token", headerPath);
        String headerResponse = "X-Auth-Token: abc123.jwt.token\n";
        assertTrue(ExtractorPathBuilder.validateExtraction(headerResponse, headerPath, token));

        String regex = ExtractorPathBuilder.buildRegexFallback(token);
        assertNotNull(regex);
        assertTrue(ExtractorPathBuilder.validateExtraction(responseBody, regex, token));
    }
}
package com.springload.strategy;

import com.springload.dto.StressConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarParserStrategyTest {

    private final HarParserStrategy strategy = new HarParserStrategy();

    private StressConfig parse(String json) {
        return strategy.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void reportsHarType() {
        assertEquals(ParserType.HAR, strategy.getType());
    }

    @Test
    void parsesJuiceHarEntriesIntoScenarios() {
        String json = """
                {
                  "log": {
                    "version": "1.2",
                    "creator": { "name": "juice" },
                    "entries": [
                      {
                        "request": {
                          "method": "GET",
                          "url": "https://api.example.com/pets/42?include=owner",
                          "headers": [
                            { "name": "Accept", "value": "application/json" }
                          ],
                          "queryString": [
                            { "name": "include", "value": "owner" }
                          ]
                        }
                      },
                      {
                        "request": {
                          "method": "POST",
                          "url": "https://api.example.com/pets",
                          "headers": [
                            { "name": "Content-Type", "value": "application/json" }
                          ],
                          "postData": {
                            "text": "{\\\"name\\\":\\\"Fido\\\"}"
                          }
                        }
                      }
                    ]
                  }
                }
                """;

        StressConfig config = parse(json);

        assertEquals("https://api.example.com", config.targetBaseUrl());
        assertEquals(2, config.scenarios().size());
        assertEquals("GET /pets/{id}", config.scenarios().get(0).name());
        assertEquals("POST /pets", config.scenarios().get(1).name());
        assertEquals("owner", config.scenarios().get(0).queryParams().get("include"));
        assertTrue(config.scenarios().get(1).body().contains("Fido"));
    }

    @Test
    void replacesNormalizedPathPlaceholdersWhenRequestBodyContainsTheCorrelatedId() {
        String json = "{"
                + "\"log\":{"
                + "\"version\":\"1.2\","
                + "\"entries\":["
                + "{\"request\":{\"method\":\"GET\",\"url\":\"https://api.example.com/polls/123456\",\"headers\":[{\"name\":\"Accept\",\"value\":\"application/json\"}]},"
                + "\"response\":{\"status\":200,\"content\":{\"text\":\"{\\\"id\\\":123456,\\\"question\\\":\\\"Which is better?\\\"}\"},\"headers\":[]}},"
                + "{\"request\":{\"method\":\"POST\",\"url\":\"https://api.example.com/polls/123456/votes\",\"headers\":[{\"name\":\"Content-Type\",\"value\":\"application/json\"}],\"postData\":{\"text\":\"{\\\"pollId\\\":123456,\\\"choice\\\":\\\"A\\\"}\"}},"
                + "\"response\":{\"status\":200,\"content\":{\"text\":\"{\\\"ok\\\":true}\"},\"headers\":[]}}"
                + "]"
                + "}"
                + "}";

        StressConfig config = parse(json);

        assertTrue(config.scenarios().get(1).path().contains("${"));
        assertTrue(config.scenarios().get(1).path().contains("pollId") || config.scenarios().get(1).path().contains("id"));
        assertTrue(!config.scenarios().get(1).path().contains("{id}"));
    }

    @Test
    void parsesPollHarWithoutRetainingOversizedBodies() throws Exception {
        try (var input = getClass().getResourceAsStream("/poll.har")) {
            StressConfig config = strategy.parse(input);
            assertEquals("http://127.0.0.1:8080", config.targetBaseUrl());
            assertTrue(config.scenarios().size() >= 10);
            assertTrue(config.scenarios().stream().anyMatch(scenario ->
                    scenario.path().contains("/auth/signin")
                            || scenario.name().contains("/auth/signin")));
        }
    }

    @Test
    void skipsOversizedCorrelationValuesToAvoidHeapPressure() {
        String largeValue = "X".repeat(2000);
        String json = "{"
                + "\"log\":{"
                + "\"version\":\"1.2\","
                + "\"entries\":["
                + "{\"request\":{\"method\":\"GET\",\"url\":\"https://api.example.com/polls/42\",\"headers\":[{\"name\":\"Accept\",\"value\":\"application/json\"}]},"
                + "\"response\":{\"status\":200,\"content\":{\"text\":\"{\\\"id\\\":42,\\\"payload\\\":\\\"" + largeValue + "\\\"}\"},\"headers\":[]}},"
                + "{\"request\":{\"method\":\"POST\",\"url\":\"https://api.example.com/polls/42/votes\",\"headers\":[{\"name\":\"Content-Type\",\"value\":\"application/json\"}],\"postData\":{\"text\":\"{\\\"pollId\\\":42,\\\"choice\\\":\\\"A\\\"}\"}},"
                + "\"response\":{\"status\":200,\"content\":{\"text\":\"{\\\"ok\\\":true}\"},\"headers\":[]}}"
                + "]"
                + "}"
                + "}";

        StressConfig config = parse(json);

        assertEquals("/polls/{id}/votes", config.scenarios().get(1).path());
    }

    @Test
    void stripsBrowserReferrerHeadersFromHarRequests() {
        String json = """
                {
                  "log": {
                    "version": "1.2",
                    "entries": [
                      {
                        "request": {
                          "method": "GET",
                          "url": "http://localhost:3000/rest/user/whoami?fields=email",
                          "headers": [
                            { "name": "Accept", "value": "application/json" },
                            { "name": "Referer", "value": "http://localhost:3000/" },
                            { "name": "Origin", "value": "http://localhost:3000" }
                          ],
                          "queryString": [
                            { "name": "fields", "value": "email" }
                          ]
                        }
                      }
                    ]
                  }
                }
                """;

        StressConfig config = parse(json);

        assertEquals("application/json", config.scenarios().get(0).headers().get("Accept"));
        assertEquals(null, config.scenarios().get(0).headers().get("Referer"));
        assertEquals(null, config.scenarios().get(0).headers().get("Origin"));
    }
}

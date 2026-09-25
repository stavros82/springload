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

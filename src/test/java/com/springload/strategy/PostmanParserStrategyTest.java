package com.springload.strategy;

import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostmanParserStrategyTest {

    private final PostmanParserStrategy strategy = new PostmanParserStrategy();

    private StressConfig parse(String json) {
        return strategy.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void reportsPostmanType() {
        assertEquals(ParserType.POSTMAN, strategy.getType());
    }

    @Test
    void parsesV210CollectionWithNestedFoldersAndVariables() {
        String json = """
                {
                  "info": { "name": "Pet Store", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
                  "variable": [ { "key": "baseUrl", "value": "https://api.petstore.io/" } ],
                  "item": [
                    {
                      "name": "Pets",
                      "item": [
                        {
                          "name": "Nested",
                          "item": [
                            {
                              "name": "Create Pet",
                              "request": {
                                "method": "post",
                                "header": [
                                  { "key": "X-Api-Key", "value": "{{apiKey}}" },
                                  { "key": "Host", "value": "api.petstore.io" },
                                  { "key": "X-Disabled", "value": "nope", "disabled": true }
                                ],
                                "auth": { "type": "bearer", "bearer": [ { "key": "token", "value": "{{token}}" } ] },
                                "body": { "mode": "raw", "raw": "{\\"id\\": \\"{{$guid}}\\", \\"owner\\": \\"{{ownerId}}\\"}" },
                                "url": {
                                  "raw": "{{baseUrl}}/pets?verbose=true",
                                  "host": [ "{{baseUrl}}" ],
                                  "path": [ "pets" ],
                                  "query": [ { "key": "verbose", "value": "{{verbose}}" } ]
                                }
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        StressConfig config = parse(json);

        assertEquals("Pet Store", config.name());
        assertEquals("https://api.petstore.io", config.targetBaseUrl());
        assertEquals(1, config.scenarios().size());

        ScenarioConfig scenario = config.scenarios().getFirst();
        assertEquals("Pets / Nested / Create Pet", scenario.name());
        assertEquals("POST", scenario.method());
        assertEquals("/pets", scenario.path());
        assertEquals("${apiKey}", scenario.headers().get("X-Api-Key"));
        assertEquals("Bearer ${token}", scenario.headers().get("Authorization"));
        assertEquals("application/json", scenario.headers().get("Content-Type"));
        assertNull(scenario.headers().get("Host"));
        assertNull(scenario.headers().get("X-Disabled"));
        assertEquals(Map.of("verbose", "${verbose}"), scenario.queryParams());
        assertEquals("{\"id\": \"${random.uuid}\", \"owner\": \"${ownerId}\"}", scenario.body());
        assertTrue(scenario.isActive());
    }

    @Test
    void parsesV200CollectionWithStringUrl() {
        String json = """
                {
                  "info": { "name": "Legacy", "schema": "https://schema.getpostman.com/json/collection/v2.0.0/collection.json" },
                  "item": [
                    {
                      "name": "List Users",
                      "request": {
                        "method": "GET",
                        "url": "https://legacy.example.com/api/users?page=2&active"
                      }
                    }
                  ]
                }
                """;

        StressConfig config = parse(json);

        assertEquals("https://legacy.example.com", config.targetBaseUrl());
        ScenarioConfig scenario = config.scenarios().getFirst();
        assertEquals("/api/users", scenario.path());
        assertEquals("2", scenario.queryParams().get("page"));
        assertEquals("", scenario.queryParams().get("active"));
    }

    @Test
    void convertsUrlEncodedBodyIntoFormPayload() {
        String json = """
                {
                  "info": { "name": "Forms" },
                  "item": [
                    {
                      "name": "Login",
                      "request": {
                        "method": "POST",
                        "url": { "host": [ "localhost:9000" ], "path": [ "login" ] },
                        "body": {
                          "mode": "urlencoded",
                          "urlencoded": [
                            { "key": "user", "value": "{{username}}" },
                            { "key": "pass", "value": "secret" },
                            { "key": "ignored", "value": "x", "disabled": true }
                          ]
                        }
                      }
                    }
                  ]
                }
                """;

        StressConfig config = parse(json);
        ScenarioConfig scenario = config.scenarios().getFirst();

        assertEquals("http://localhost:9000", config.targetBaseUrl());
        assertEquals("/login", scenario.path());
        assertEquals("user=${username}&pass=secret", scenario.body());
        assertEquals("application/x-www-form-urlencoded", scenario.headers().get("Content-Type"));
    }

    @Test
    void translatesDynamicPostmanVariables() {
        assertEquals("${random.uuid}", strategy.translate("{{$randomUUID}}"));
        assertEquals("${timestamp}", strategy.translate("{{$timestamp}}"));
        assertEquals("${random(1-1000)}", strategy.translate("{{$randomInt}}"));
        assertEquals("/pets/${petId}/tags", strategy.translate("/pets/{{ petId }}/tags"));
        assertEquals("plain", strategy.translate("plain"));
    }

    @Test
    void rejectsCollectionWithoutItems() {
        assertThrows(RuntimeException.class, () -> parse("{\"info\": {\"name\": \"Empty\"}}"));
    }
}

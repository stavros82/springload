package com.springload.strategy;

import com.springload.dto.StressConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YamlParserStrategyTest {

    private final YamlParserStrategy strategy = new YamlParserStrategy();

    private StressConfig parse(String yaml) {
        return strategy.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void reportsYamlType() {
        assertEquals(ParserType.YAML, strategy.getType());
    }

    @Test
    void normalizesPathVariablesToBindableTokens() {
        String yaml = """
                name: PetClinic
                targetBaseUrl: http://localhost:8080
                scenarios:
                  - name: Update Owner
                    method: PUT
                    path: /owners/${ownerId}
                    weight: 50
                  - name: Owner Pet
                    method: GET
                    path: /owners/:ownerId/pets/{petId}
                    weight: 50
                """;

        StressConfig config = parse(yaml);

        assertEquals("/owners/${ownerId}", config.scenarios().get(0).path());
        assertEquals("/owners/{ownerId}/pets/{petId}", config.scenarios().get(1).path());
    }

    @Test
    void collapsesAccumulatedDollarPrefixes() {
        assertEquals("/owners/${ownerId}", YamlParserStrategy.normalizePath("/owners/$${ownerId}"));
        assertEquals("/owners/${random(1-100)}", YamlParserStrategy.normalizePath("/owners/$$${random(1-100)}"));
    }

    @Test
    void keepsDynamicResolverExpressions() {
        assertEquals("/pets/${random.uuid}", YamlParserStrategy.normalizePath("/pets/${random.uuid}"));
        assertEquals("/pets/${random(1-100)}", YamlParserStrategy.normalizePath("/pets/${random(1-100)}"));
        assertEquals("/events/${timestamp}", YamlParserStrategy.normalizePath("/events/${timestamp}"));
        assertEquals("/owners/${ownerId}/visits/${timestamp}",
                YamlParserStrategy.normalizePath("/owners/${ownerId}/visits/${timestamp}"));
    }

    @Test
    void preservesExtractedFlowVariablesFromExistingStressYaml() {
        StressConfig config = parse("""
                name: Imported
                scenarios:
                  - name: Create
                    method: POST
                    path: /graphql
                    body: '{"id":"${categoryId}"}'
                    extractedVariables:
                      categoryId: $.data.id
                """);

        assertEquals("$.data.id",
                config.scenarios().get(0).extractedVariables().get("categoryId"));
        assertEquals("{\"id\":\"${categoryId}\"}", config.scenarios().get(0).body());
    }
}

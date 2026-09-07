package com.springload.strategy;

import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class JmeterParserStrategyTest {

    private final JmeterParserStrategy strategy = new JmeterParserStrategy();

    private StressConfig parseResource(String resourceName) throws Exception {
        try (InputStream is = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(resourceName),
                "Resource not found: " + resourceName)) {
            return strategy.parse(is);
        }
    }

    private StressConfig parseXml(String xml) {
        return strategy.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void reportsJmeterType() {
        assertEquals(ParserType.JMETER, strategy.getType());
    }

    @Test
    void parsesBaselineSamplerFromSpecSample() {
        String jmx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jmeterTestPlan version="1.2" properties="5.0">
                  <hashTree>
                    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Test Plan" enabled="true"/>
                    <hashTree>
                      <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="Get System Status" enabled="true">
                        <stringProp name="HTTPSampler.domain">api.system.local</stringProp>
                        <stringProp name="HTTPSampler.port">8080</stringProp>
                        <stringProp name="HTTPSampler.protocol">http</stringProp>
                        <stringProp name="HTTPSampler.path">/api/v1/status</stringProp>
                        <stringProp name="HTTPSampler.method">GET</stringProp>
                      </HTTPSamplerProxy>
                    </hashTree>
                  </hashTree>
                </jmeterTestPlan>
                """;

        StressConfig config = parseXml(jmx);

        assertEquals(1, config.scenarios().size());
        ScenarioConfig scenario = config.scenarios().getFirst();
        assertEquals("Get System Status", scenario.name());
        assertEquals("GET", scenario.method());
        assertEquals("/api/v1/status", scenario.path());
        assertEquals("http://api.system.local:8080", config.targetBaseUrl());
    }

    @Test
    void parsesPetclinicBenchmarkFile() throws Exception {
        StressConfig config = parseResource("petclinic-jmeter-crud-benchmark.jmx");

        // All 8 samplers have no enabled=false, so all should be included
        assertEquals(8, config.scenarios().size());

        // Base URL must come from ConfigTestElement defaults
        assertEquals("http://localhost:9966", config.targetBaseUrl());

        // Headers from HeaderManager
        assertEquals("application/json", config.scenarios().getFirst().headers().get("Content-Type"));
        assertEquals("application/json", config.scenarios().getFirst().headers().get("Accept"));

        // POST body on Create Owner
        ScenarioConfig createOwner = config.scenarios().stream()
                .filter(s -> s.name().equals("Create Owner"))
                .findFirst().orElseThrow();
        assertEquals("POST", createOwner.method());
        assertNotNull(createOwner.body());
        assertTrue(createOwner.body().contains("firstName"));

        // Path variables preserved
        ScenarioConfig addPet = config.scenarios().stream()
                .filter(s -> s.name().equals("Add Pet to Owner"))
                .findFirst().orElseThrow();
        assertEquals("/petclinic/api/owners/${owner_id}/pets", addPet.path());

        // GET has no body
        ScenarioConfig getPet = config.scenarios().stream()
                .filter(s -> s.name().equals("Get Pet Belonging to Owner"))
                .findFirst().orElseThrow();
        assertNull(getPet.body());
    }

    @Test
    void extractsQueryParameters() throws Exception {
        StressConfig config = parseResource("test-plan.jmx");

        ScenarioConfig search = config.scenarios().stream()
                .filter(s -> s.name().equals("Search Owners"))
                .findFirst()
                .orElseThrow();

        assertEquals("Smith", search.queryParams().get("lastName"));
    }

    @Test
    void extractsRawBody() throws Exception {
        StressConfig config = parseResource("test-plan.jmx");

        ScenarioConfig create = config.scenarios().stream()
                .filter(s -> s.name().equals("Create Owner"))
                .findFirst()
                .orElseThrow();

        assertEquals("POST", create.method());
        assertEquals("{\"name\": \"John\"}", create.body());
    }

    @Test
    void extractsHeadersFromHeaderManager() throws Exception {
        StressConfig config = parseResource("test-plan.jmx");

        // Headers from HeaderManager are applied to all scenarios
        ScenarioConfig any = config.scenarios().getFirst();
        assertEquals("Bearer ${AUTH_TOKEN}", any.headers().get("Authorization"));
        assertEquals("application/json", any.headers().get("Content-Type"));
    }

    @Test
    void skipsDisabledSamplers() {
        String jmx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jmeterTestPlan version="1.2" properties="5.0">
                  <hashTree>
                    <HTTPSamplerProxy testname="Active" enabled="true">
                      <stringProp name="HTTPSampler.domain">localhost</stringProp>
                      <stringProp name="HTTPSampler.protocol">http</stringProp>
                      <stringProp name="HTTPSampler.path">/active</stringProp>
                      <stringProp name="HTTPSampler.method">GET</stringProp>
                    </HTTPSamplerProxy>
                    <HTTPSamplerProxy testname="Disabled" enabled="false">
                      <stringProp name="HTTPSampler.domain">localhost</stringProp>
                      <stringProp name="HTTPSampler.protocol">http</stringProp>
                      <stringProp name="HTTPSampler.path">/disabled</stringProp>
                      <stringProp name="HTTPSampler.method">GET</stringProp>
                    </HTTPSamplerProxy>
                  </hashTree>
                </jmeterTestPlan>
                """;

        StressConfig config = parseXml(jmx);

        assertEquals(1, config.scenarios().size());
        assertEquals("/active", config.scenarios().getFirst().path());
    }

    @Test
    void normalizesPathWithoutLeadingSlash() {
        String jmx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jmeterTestPlan version="1.2" properties="5.0">
                  <hashTree>
                    <HTTPSamplerProxy testname="No Slash" enabled="true">
                      <stringProp name="HTTPSampler.domain">localhost</stringProp>
                      <stringProp name="HTTPSampler.protocol">http</stringProp>
                      <stringProp name="HTTPSampler.path">api/health</stringProp>
                      <stringProp name="HTTPSampler.method">GET</stringProp>
                    </HTTPSamplerProxy>
                  </hashTree>
                </jmeterTestPlan>
                """;

        StressConfig config = parseXml(jmx);

        assertEquals("/api/health", config.scenarios().getFirst().path());
    }

    @Test
    void throwsOnMalformedXml() {
        assertThrows(RuntimeException.class, () -> parseXml("<not valid xml"));
    }
}

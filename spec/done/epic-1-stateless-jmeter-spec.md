# Specification: Baseline JmeterParserStrategy (Stateless XML Ingestion)

## 1. Overview
The goal of this specification is to establish the baseline **JMeter Ingestion Strategy** [cite: 24]. Since our pluggable parser strategy factory already supports HAR and Swagger ingestion [cite: 24, 152], adding baseline Apache JMeter support completes our multi-source strategy mapping capabilities [cite: 21, 22].

This strategy traverses standard JMeter `.jmx` XML documents [cite: 9], isolates HTTP requests (samplers), and translates them cleanly into stateless `ScenarioRoute` POJO configurations for standard YAML execution [cite: 24, 26, 145].

---

## 2. Technical Target Files
- **Factory Registration:** `com.springload.controller.ParserController` or corresponding strategic mapping factory [cite: 145].
- **New Parser Strategy:** `src/main/java/com/springload/strategy/JmeterParserStrategy.java` [cite: 145].
- **Test Suite:** `src/test/java/com/springload/strategy/JmeterParserStrategyTest.java` [cite: 149].

---

## 3. Technical Requirements

### Step A: Strategy Registration & Signature Binding
1. Create `JmeterParserStrategy` implementing your pluggable parser strategy interface [cite: 145, 146].
2. Update the system parser factory to detect incoming XML uploads containing the JMeter test plan schema signature:
   - Check if the uploaded file has a `.jmx` extension [cite: 24].
   - Or scan the starting line of the uploaded text for the XML node tag: `<jmeterTestPlan` [cite: 9].

### Step B: JMX XML Traversal Engine (Non-Blocking DOM/SAX)
JMeter files are deeply nested tree representations of test elements [cite: 9]. Use standard, non-blocking Java DOM/SAX parsing to traverse the nodes:
1. Locate every occurrence of the **`HTTPSamplerProxy`** XML element tag, representing terminal client requests [cite: 146].
2. Map each active sampler to a single `ScenarioRoute` model [cite: 147].
3. Discard or ignore non-request nodes (e.g., UI listener panels, cookie manager defaults, thread group metadata) [cite: 146, 149].

### Step C: Request Property Extraction
For each isolated `HTTPSamplerProxy` node, map attributes recursively to our configuration POJOs [cite: 26, 147]:
1. **Target URI & Path:**
   - Read sub-elements `domain` (host), `port`, `protocol` (HTTP/HTTPS), and `path` to construct the route endpoint [cite: 147].
2. **HTTP Method:**
   - Extract the method attribute (e.g., `GET`, `POST`, `PUT`) [cite: 147].
3. **Query Parameters:**
   - Parse sub-element arrays mapping `elementProp` configurations under the argument list. Extract key-value parameter pairs.
4. **Header Managers:**
   - Traverse the sibling XML trees to locate nested `HeaderManager` nodes associated with each sampler. Map header properties directly [cite: 147].
5. **Request Body:**
   - Extract raw text or multi-part JSON parameters defined under the post-body parameters.

---

## 4. Sample Test Plan Resource (`test-plan.jmx`)
Devin can use this mock XML file to write his validation tests:
```xml
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
```

---

## 5. Verification Protocol (DoD)
- [ ] Uploading `test-plan.jmx` compiles cleanly via Maven without strategic registration errors [cite: 149].
- [ ] Baseline HTTP endpoints parse accurately into standard flat JSON/YAML configurations [cite: 26, 149].
- [ ] Baseline unit tests verify DOM parser element collection [cite: 149].

# Specification: Deterministic Stateful Ingestion Extension (Postman & JMeter)

## 1. Overview
Realistic performance tests must simulate stateful user flows (e.g., logging in to capture dynamic tokens for downstream requests) [cite: 64, 122]. Because Postman and JMeter collections are pre-engineered test assets, their variable dependencies are already explicitly declared [cite: 9, 144]. 

This specification overlays **deterministic translation** onto both strategies [cite: 153, 154]. It bypasses probabilistic runtime scans [cite: 5, 156], translating Postman's JavaScript `pm.environment.set` APIs and JMeter's nested XML Post-Processors directly into our native, declarative route `extract` YAML blocks [cite: 6, 26, 153, 154].

---

## 2. Technical Target Files
- **Postman Script Parser:** `src/main/java/com/springload/util/PostmanScriptTranslator.java` [cite: 144, 152].
- **JMeter Extractor Parser:** Integrated into `src/main/java/com/springload/strategy/JmeterParserStrategy.java` [cite: 152].
- **Test Suites:** 
  - `src/test/java/com/springload/util/PostmanScriptTranslatorTest.java` [cite: 149].
  - `src/test/java/com/springload/strategy/JmeterStatefulStrategyTest.java` [cite: 149].

---

## 3. Technical Specifications

### Part A: Postman Script Translation (`PostmanScriptTranslator.java`)
1. **Script Scanning:** Scan collection JSON for `event` blocks where `listen == "test"` [cite: 154].
2. **Regex JS Extractor:** Match environment/global variable writes using this end-anchored greedy matching pattern:
   ```regex
   pm\.(environment|globals|variables)\.set\(\s*["']([^"']+)["']\s*,\s*(.+)\s*\);?\s*$
   ```
   *This pattern consumes inner brackets (like `json()`) without breaking on statement boundaries [cite: 154].*
3. **Expression Converters:**
   - **JSON Body:** Translate `pm.response.json().path` paths to JSONPath expressions (e.g., `$.user.id`) [cite: 6, 154].
   - **Headers:** Translate `pm.response.headers.get("X-Token")` fetches to standard selectors (e.g., `header:X-Token`) [cite: 6, 153].
4. **Implementation Reference Logic:**
   ```java
   package com.springload.util;
   
   import java.util.*;
   import java.util.regex.*;

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

       public static Map<String, String> translate(List<String> scriptLines) {
           Map<String, String> extractions = new HashMap<>();
           if (scriptLines == null) return extractions;
           for (String line : scriptLines) {
               line = line.trim();
               if (line.isEmpty() || line.startsWith("//") || line.startsWith("/*")) continue;
               Matcher setMatcher = SET_PATTERN.matcher(line);
               if (setMatcher.find()) {
                   String varName = setMatcher.group(2);
                   String valueExpression = setMatcher.group(3).trim();
                   if (valueExpression.endsWith(")")) {
                       long open = valueExpression.chars().filter(ch -> ch == '(').count();
                       long close = valueExpression.chars().filter(ch -> ch == ')').count();
                       if (close > open) valueExpression = valueExpression.substring(0, valueExpression.length() - 1).trim();
                   }
                   String translated = translateExpression(valueExpression);
                   if (translated != null) extractions.put(varName, translated);
               }
           }
           return extractions;
       }

       private static String translateExpression(String expr) {
           Matcher jsonMatcher = JSON_ACCESS_PATTERN.matcher(expr);
           if (jsonMatcher.find()) {
               String props = jsonMatcher.group(1);
               return (props == null || props.isEmpty()) ? "$" : "$." + props;
           }
           Matcher headerMatcher = HEADER_GET_PATTERN.matcher(expr);
           if (headerMatcher.find()) {
               return "header:" + headerMatcher.group(1);
           }
           return null;
       }
   }
   ```

### Part B: JMeter XML Post-Processor Translation
1. **Node Traversal:** During XML SAX/DOM parsing, inspect children or siblings nested directly under each `HTTPSamplerProxy` xml node [cite: 146, 153].
2. **Translate `JSONPostProcessor`:**
   - Locate the `JSONPostProcessor` element tag [cite: 153].
   - Extract string value attributes: `referenceNames` (target variable) and `jsonPathExprs` (JSONPath) [cite: 153].
   - Inject key-value pairs directly into the route’s declarative `extract` block [cite: 153].
3. **Translate `RegexExtractor`:**
   - Locate the `RegexExtractor` element tag [cite: 153].
   - Extract string value attributes: `refVal` (variable), `regex` (pattern), and `template` (capture group index) [cite: 153].
   - Output our standardized regex extractor rule format under the `extract` map [cite: 6, 153].

---

## 4. Verification Protocol (DoD)
- [ ] Postman collections setting dynamic environment variables are successfully translated into explicit JSONPath route extract configurations [cite: 144, 154].
- [ ] Nested JMeter `JSONPostProcessors` parse cleanly into explicit `ScenarioRoute.extract` mappings [cite: 153].
- [ ] Unit tests pass, proving correct mapping boundaries without invoking runtime background traffic analyzers [cite: 149].

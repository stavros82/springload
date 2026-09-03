# Specification: Probabilistic HAR Auto-Correlation Engine

## 1. Overview
Browser Network traces (HAR archives) are raw dumps of client-server network logs [cite: 4, 10, 41]. Because they are captured passively, they contain no pre-existing script variables, environment bindings, or test selectors [cite: 10, 41]. 

The goal of this epic is to implement a **Probabilistic Auto-Correlation Engine** inside `HarParserStrategy` [cite: 5, 24]. The engine scans raw response bodies, maps matching parameters dynamically to downstream requests, scores candidate tokens, and automatically parameterises the resulting declarative `stress.yaml` export blueprint [cite: 5, 6, 26, 154].

---

## 2. Technical Target Files
- **Auto-Correlation Strategy:** `src/main/java/com/springload/strategy/HarParserStrategy.java` [cite: 152].
- **Core Helpers (Optional):** Create helper classes under `com.springload.util.correlation` to manage dependencies.
- **Verification Tests:** `src/test/java/com/springload/strategy/HarAutoCorrelationTest.java` [cite: 149].

---

## 3. Technical Specifications

### Phase 1: Multi-Pass Response-Request Value Matcher
1. **Response Capture Storage:** Traverse all entries in the uploaded HAR log file, keeping an sequential cache of each response body and header payload [cite: 41, 154].
2. **Sequential Comparison Pass:** Loop through subsequent entries [cite: 154]. Compare the string value of any downstream request headers, path strings, query parameters, or JSON bodies against the stored values of preceding response payloads [cite: 5, 154].
3. **Trace Candidate Extraction:** If a string match of length $\ge 6$ is found, flag the matching string value as a **Correlation Candidate** [cite: 5].

### Phase 2: Entropy & Uniqueness Scorer
To prevent false-positive parameters (such as replacing common digits like `0` or `1`, or standard strings like `"success"` or `"application/json"`), run each candidate through an **uniqueness evaluation logic** [cite: 5]:
- **High-Uniqueness Score:** Values matching standard UUID patterns, base64 JWT signatures, or high-entropy alpha-numeric strings (length $> 16$) receive a high score [cite: 5, 154]. These are flagged as **safe for global substitution** across all downstream paths [cite: 5, 154].
- **Low-Uniqueness Score:** Values containing simple counters or short integers (e.g., matching SQL ID increments) receive a low score [cite: 5]. These must be ignored, or constrained strictly using **boundary-aware, localized path constraints** to prevent corrupting adjacent values [cite: 5, 6].

### Phase 3: Dynamic Path Selector Generation
For each high-scoring candidate, construct a valid, precise extraction selector depending on the source media type [cite: 6]:
1. **JSONPath Builder:** If the value originates inside a JSON response body, traverse the parsed JSON tree programmatically to construct a valid JSONPath expression pointing to that key (e.g., `$.session.authToken`) [cite: 6, 153].
2. **Header Selector Builder:** If the value originates in an HTTP response header, map it to our native header query pattern: `header:<HeaderName>` (or `header:<HeaderName>:<sub-key>` for nested elements like cookie parameters) [cite: 6, 153].
3. **Regex Fallback Builder:** For HTML or plain text bodies, construct a localized regular expression capture group centered around the target value [cite: 6, 153].
4. **Validation Test Run:** Compile each generated rule and run it against the source response. Verify that the output exactly matches the candidate string before committing [cite: 6].

### Phase 4: Auto-Parameterisation & Blueprint Compilation
1. **Placeholder Replacement:** Replace the hardcoded matching strings in all downstream requests (paths, queries, headers, bodies) with our dynamic template placeholder `${var_name}` [cite: 6, 26, 154].
2. **Extraction Map Injection:** Append the generated extraction paths directly into the corresponding upstream `ScenarioRoute` models' `extract` blocks [cite: 153, 154].
3. **YAML Serialization:** Export the final, state-chained blueprint config via the standard `/api/v1/parse/export` controller [cite: 24, 154].

---

## 4. Verification Protocol (DoD)
- [ ] Importing a multi-step login/retrieve HAR trace successfully auto-extracts the dynamic token into a route `extract` mapping [cite: 26, 152].
- [ ] Downstream routes are parameterized with matching dynamic variables `${variable}` [cite: 26, 154].
- [ ] Low-entropy strings (e.g. database ID `1`) are not replaced globally across unrelated fields [cite: 5].
- [ ] Unit tests verify end-to-end trace correlation accuracy on mock HAR inputs [cite: 149].

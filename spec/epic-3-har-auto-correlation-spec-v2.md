# Specification: Probabilistic HAR Auto-Correlation Engine (v2)

## 1. Executive Summary & Architectural Goals
Browser Network traces (HAR files) are raw, unscripted dumps of client-server HTTP transactions [cite: 4, 10, 41]. They record exact request and response payloads but contain no variable bindings, scripting context, or test selectors [cite: 10, 41]. 

The goal of **Epic 3** is to implement a **Probabilistic Auto-Correlation Engine** inside `HarParserStrategy` [cite: 5, 24]. The engine compresses what used to take 5–25 hours of manual regex scripting into a **sub-2-minute automated pipeline** [cite: 1, 4]. 

### Core Architectural Principles:
1. **Ingestion-Time Intelligence:** All probabilistic scanning, uniqueness scoring, and extractor generation runs *once* during file upload [cite: 24, 154].
2. **Deterministic Runtime Execution:** The exported `stress.yaml` contains explicit `extract` rules and `${variable}` placeholders [cite: 26, 153, 154]. The live execution engines (`VIRTUAL_THREADS` and `REACTIVE`) execute with **zero runtime scanning or regex CPU overhead** [cite: 23, 156].
3. **Contract Regression Checking:** Parameterized chains act as an automated regression suite, catching backend API schema shifts (e.g., broken JSONPaths) before production deployment [cite: 7, 8, 168].

---

## 2. Technical Target Files & Package Structure
- **Auto-Correlation Parser:** `src/main/java/com/springload/strategy/HarParserStrategy.java` [cite: 152]
- **Value Matcher & Scanner:** `src/main/java/com/springload/util/correlation/HarCorrelationScanner.java`
- **Uniqueness & Entropy Scorer:** `src/main/java/com/springload/util/correlation/UniquenessScorer.java`
- **Extraction Path Generator:** `src/main/java/com/springload/util/correlation/ExtractorPathBuilder.java`
- **Verification Test Suite:** `src/test/java/com/springload/strategy/HarAutoCorrelationTest.java` [cite: 149]

---

## 3. Modular Sub-Issue Roadmap & Copilot Sprint Plan

---

### 📅 PHASE 1: September Sprint (Burn Remaining Free Credits)

#### Sub-Issue 3.1: `[Backend] HAR Ingestion: Noise Filtering, Value Matcher & Uniqueness Scorer`
* **Target Copilot Cost:** ~20–25% of free quota
* **Status:** PENDING (Ready for September Sprint)

**Technical Specifications:**
1. **Noise Filtering Pass:** Prior to correlation scanning, filter out entries targeting static assets (`.css`, `.js`, `.png`, `.jpg`, `.gif`, `.svg`, `.woff2`, `.ico`) and non-SUT third-party domains (analytics, fonts, external CDNs) [cite: 4, 22].
2. **Multi-Pass Observation Scanner:**
   * Traverse active log entries sequentially [cite: 41, 154].
   * Cache all response body strings and response headers [cite: 41, 154].
   * Compare strings (length $\ge 6$) found in each response against all subsequent request URLs, headers, query parameters, and JSON request bodies [cite: 5, 154].
3. **Entropy & Uniqueness Scoring:**
   * **High-Uniqueness Score:** Standard 36-char UUIDs (`[0-9a-fA-F]{8}-[0-9a-fA-F]{4}...`), JWT tokens (`eyJ...`), or high-entropy alphanumeric strings (length $> 16$) receive high scores [cite: 5, 154]. Flagged as **safe for global parameter replacement** across all downstream requests [cite: 5, 154].
   * **Low-Uniqueness Score:** Simple integers, short database IDs (e.g. `"1"`, `"42"`), or short common strings receive low scores [cite: 5]. These are restricted to **boundary-aware, localized JSONPath replacements** to prevent corrupting adjacent fields (like quantities or API versions) [cite: 5, 6].

**Copilot Prompt for Sub-Issue 3.1:**
```text
Please implement Sub-Issue 3.1 for SpringLoad:
1. Create com.springload.util.correlation.UniquenessScorer to evaluate candidate strings. Use UUID regex, JWT prefix checks, and entropy scoring to return HIGH vs LOW uniqueness.
2. Create com.springload.util.correlation.HarCorrelationScanner to filter out static asset paths (.css, .js, .png, etc.) and perform a sequential comparison of response payload values against downstream request parameters.
3. Write unit tests in HarAutoCorrelationTest.java asserting high uniqueness for UUIDs/JWTs and localized handling for simple IDs.
```

---

#### Sub-Issue 3.2: `[Backend] HAR Ingestion: Dynamic Extraction Path Generation & Proofing`
* **Target Copilot Cost:** ~15–20% of free quota
* **Status:** PENDING (Ready for September Sprint)

**Technical Specifications:**
1. **Precision Extractor Builder:**
   * **JSONPath Generator:** For JSON response bodies, traverse the JSON tree to programmatically build exact selectors (e.g., `$.data.session.token`) [cite: 6, 153].
   * **Header Selector Builder:** Map response header matches to standard header extraction queries (`header:Set-Cookie` or `header:Authorization`) [cite: 6, 153].
   * **Regex Fallback Builder:** For HTML or plain text payloads, construct localized regex capture groups centered around the target candidate string [cite: 6, 153].
2. **Extractor Proofing Engine:**
   * Before accepting an extraction rule, programmatically compile and run it against the captured source response body/header [cite: 6, 7].
   * Confirm the extracted output matches the expected candidate string [cite: 6]. If verification fails, fall back to localized regex or discard [cite: 6, 7].

**Copilot Prompt for Sub-Issue 3.2:**
```text
Please implement Sub-Issue 3.2 for SpringLoad:
1. Create com.springload.util.correlation.ExtractorPathBuilder to generate JSONPath (using Jackson/JsonPath), Header selectors (header:Name), and Regex fallback patterns for candidate values.
2. Add a proofing check method validateExtraction(String responseBody, String path, String expectedValue) to verify extraction rules against the recorded response before committing.
3. Add unit tests in HarAutoCorrelationTest.java verifying JSONPath and Header path generation.
```

---

### 📅 PHASE 2: October 1st Reset Sprint (Fresh Credit Quota)

#### Sub-Issue 3.3: `[Backend] HAR Ingestion: Dynamic Parameterisation, Blueprint Export & Contract Regression Testing`
* **Status:** SCHEDULED (October 1st Reset)

**Technical Specifications:**
1. **Dynamic Parameter Substitution:** Substitute hardcoded target strings in downstream request URLs, headers, and JSON bodies with dynamic variable placeholders `${var_name}` [cite: 6, 26, 154].
2. **Declarative Extract Mapping:** Inject generated extraction rules directly into the corresponding upstream `ScenarioRoute` model's `extract` configuration block [cite: 153, 154].
3. **Soft Failure Assertion Generator:** Generate validation rules beyond HTTP status codes (e.g. flagging HTTP 200 responses containing `"success": false` or unexpected login form landing pages) [cite: 5].
4. **Contract Regression Protection:** If an upstream extraction path fails to resolve during a benchmark run (yielding `null` or unpopulated `${var}` strings due to a backend API schema change), flag the execution step as a **Contract Regression Failure** [cite: 7, 8, 168].

**Copilot Prompt for Sub-Issue 3.3:**
```text
Please implement Sub-Issue 3.3 for SpringLoad:
1. Integrate HarCorrelationScanner and ExtractorPathBuilder into HarParserStrategy.
2. Replace downstream matching request values with ${variable} placeholders and inject extract mappings into upstream ScenarioRoute objects.
3. Add soft failure assertion checks for responses containing 'success: false'.
4. Ensure exported stress.yaml contains valid state-chained extract nodes.
```

---

#### Sub-Issue 3.4 (Security Add-on): `[Backend] HAR Sanitization & Sensitive Header Masking`
* **Status:** SCHEDULED (October 1st Reset)

**Technical Specifications:**
1. **Ingestion-Time Sanitizer:** Implement client-side or ingest-time sanitization (Cloudflare HAR Sanitizer pattern) [cite: 46, 118].
2. **Credential & Signature Masking:** Automatically strip sensitive passwords, secret keys, Bearer token signatures, and authentication cookie values prior to storing or sharing HAR files [cite: 46, 118].

---

## 4. Definition of Done (DoD)
- [ ] Static assets (`.js`, `.css`, images) are cleanly filtered out during HAR ingestion [cite: 4, 22].
- [ ] High-entropy values (UUIDs, JWTs) are correctly scored and parameterized globally [cite: 5, 154].
- [ ] Low-entropy values (simple IDs like `1`) are localized to strict JSONPath boundaries [cite: 5, 6].
- [ ] Generated JSONPath and Header extraction rules pass the proofing verification check against source responses [cite: 6, 7].
- [ ] Exported `stress.yaml` blueprints contain valid `extract` mappings and `${var}` placeholders [cite: 26, 153, 154].
- [ ] End-to-end unit tests pass in `HarAutoCorrelationTest.java` [cite: 149].

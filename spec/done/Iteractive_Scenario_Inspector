# Specification: Interactive Scenario Inspector UI & Dynamic Variable Resolver

## 1. Overview
The goal of this feature is to implement a visual **Scenario Inspector Studio** in the frontend and connect it to our existing Spring Boot parser and YAML exporter APIs (`POST /api/v1/parse` and `POST /api/v1/parse/export`) [cite: 24, 124].

Additionally, the runtime execution engines must be updated to dynamically evaluate dynamic placeholder variables (such as `${random(1-100)}` and `${random.uuid}`) on every request iteration [cite: 26]. This ensures that each concurrent request carries unique, realistic parameter values—preventing target database or application caches from biasing load test benchmarks [cite: 66].

---

## 2. Codebase Reference Map
- **Frontend Workspace View:** `src/main/resources/static/index.html` (the primary control center) [cite: 27].
- **Backend Entrypoint:** `com.springload.controller.ParserController` (exposes parse normalization and YAML compilation endpoints) [cite: 24].
- **Backend Parsers:** `com.springload.strategy.SwaggerParserStrategy` (extracts and normalizes dynamic path variables) [cite: 24].
- **Runtime Execution Engines:**
  - `VirtualThreadExecutionService` (Java 21 `newVirtualThreadPerTaskExecutor()`) [cite: 23].
  - `ReactiveExecutionService` (Spring WebFlux `WebClient`) [cite: 23].
- **Declarative Spec POJO:** `com.springload.config.StressConfig` (Jackson-mapped configuration blueprint) [cite: 26].

---

## 3. Technical Specifications

### Phase 1: Frontend Workspace UI (index.html)
1. **Workspace State:** Reveal a hidden container `#scenario-studio` once the user's drag-and-dropped HAR or OpenAPI spec parses successfully via `POST /api/v1/parse` [cite: 27, 126].
2. **Visual Toggles:** Render all returned endpoints as interactive cards.
   - Bind an on/off toggle switch to the newly added `active` field in the target schema [cite: 20].
3. **Weight Sliders:**
   - Render numeric slider inputs (`0-100%`) for active endpoints.
   - Implement a real-time event listener to sum these active weights. Disable the **Launch Stress Test** and **Export stress.yaml** buttons if the sum does not equal exactly `100%`, showing a validation warning [cite: 125].
4. **Variable Override Dropdowns:**
   - Detect path placeholders extracted by `SwaggerParserStrategy` (e.g., `{id}`) [cite: 24].
   - Provide a select dropdown next to each path variable:
     - **Static Value:** Renders a simple text box (e.g., `101`).
     - **Random Range:** Renders Min and Max number fields that compile reactively to `${random(Min-Max)}` syntax [cite: 26].
     - **UUID:** Maps the variable to `${random.uuid}` [cite: 26].
5. **JSON Payload and Header Overrides:** Renders dynamic textareas to let users customize HTTP headers and body payloads per route [cite: 125].

---

### Phase 2: Dynamic Variable Resolver Specification
Implement a dynamic variable expression evaluator utility on the backend [cite: 26]. The agent is free to design the class structure, package location, helper methods, and parsing libraries, but must satisfy the following criteria:

1. **Input Interface:**
   - Expose a thread-safe utility or service method that accepts a template string (representing URL paths, header maps, or JSON body payloads) and returns a fully resolved string [cite: 26].
2. **Supported Expression Parsers:**
   - **`${random(min-max)}`**: Match integer ranges (e.g., `${random(1-100)}`) and substitute them with a pseudo-random integer within the specified range (inclusive) [cite: 26].
   - **`${random.uuid}`**: Match and substitute with a freshly generated random UUID [cite: 26].
   - **`${timestamp}`**: Match and substitute with the current epoch timestamp in milliseconds.
3. **Performance Constraints:**
   - **Thread Safety:** The resolver must be completely thread-safe and lock-free, as it will be executed concurrently by thousands of lightweight virtual threads [cite: 23].
   - **Concurrency Optimization:** Avoid heavy locks or synchronizations. For random integer generation under high virtual thread concurrency, use non-blocking utilities like `ThreadLocalRandom.current()` [cite: 23].
   - **Bypass Optimization:** Optimize the execution path so that strings without placeholder prefixes (like `${`) bypass regex matching entirely to maintain high benchmark throughput.

---

### Phase 3: Runtime Execution Loop Hydration

#### 1. Virtual Threads Execution (`VirtualThreadExecutionService`)
Update the imperative execution loop to resolve dynamic parameters *per execution loop task* right before firing the standard Java `HttpClient` request [cite: 23, 26]:
- Before dispatching, run your dynamic resolver on the URI path, header maps, and request body templates.
- Ensure that this resolution happens *inside* the thread execution loop block so that every single iteration evaluates fresh, randomized values [cite: 23, 26].

#### 2. Reactive Execution (`ReactiveExecutionService`)
Update the WebFlux reactive stream pipeline to evaluate variables deferredly per subscription, avoiding any blocking threads in Netty's event loop [cite: 23]:
- Wrap template resolution inside a deferred assembly block (such as using deferred mapping or reactive map transformations) [cite: 23].
- Ensure regex matching does not introduce blocking operations that stall the non-blocking Netty event loops [cite: 23].

---

## 4. Verification & Definition of Done (DoD)
- [ ] UI correctly maps parsed path variables and compiles custom ranges into `${random(min-max)}` syntax [cite: 26, 125].
- [ ] Exported `stress.yaml` configurations successfully serialize dynamic variables as standard string properties without throwing parser exceptions in Jackson [cite: 26].
- [ ] Active execution runs execute unique, parameterized HTTP requests per iteration (preventing target-side caching bias) [cite: 66].
- [ ] No blocking threads or performance bottlenecks are introduced inside execution pipelines [cite: 23].

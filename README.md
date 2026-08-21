# 🚀 HarStress (Project SpringLoad)

> **Multi-Engine, Multi-Source Load Testing Engine for Modern Java Backend Teams.**

Skip manual request mapping and complex scripting. HarStress ingests browser Network traces (`.har`), OpenAPI/Swagger specifications, and API collections (such as Postman), parses them via a strategy pipeline into a clean `stress.yaml` blueprint, and executes high-concurrency stress tests using either **Java 21 Virtual Threads** or **Spring WebFlux (Reactive)**.

## ✨ Core Features

*   **Runtime Multi-Engine Selection:** Select between **Java 21 Virtual Threads** and **Spring WebFlux (Reactive)** directly from the Web UI drop-down menu per test run—no Spring Profiles or server restarts required.
*   **Multi-Source Strategy Parsing:** Pluggable parser engine using the Strategy Pattern to ingest browser `.har` network exports, OpenAPI/Swagger specifications, and API collections into a unified blueprint model.
*   **Smart Asset Filtering & Sanitization:** Automatically filters out static assets (`.css`, `.js`, `.png`), parameterizes dynamic paths (e.g., turning `/api/owners/1` into `/api/owners/{id}`), and strips restricted transport headers.
*   **Config-Driven & UI-Driven Setup:** Define test scenarios via a declarative `stress.yaml` file or directly through the web dashboard.
*   **Declarative Blueprint Export:** Review and customize parsed scenarios on the interactive web dashboard and export finalized blueprints as standard `stress.yaml` files.
*   **Real-time Streaming Metrics:** Stream real-time performance metrics (RPS, p50/p95/p99 latency, error rates) to the UI using Server-Sent Events (SSE).

## ⚙️ Runtime Load Engine Selection

HarStress allows selecting the execution engine dynamically from the UI drop-down control panel:

| Engine | Tech Stack | Best For |
| :--- | :--- | :--- |
| **Virtual Threads** | Java 21+ Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) | High concurrency with familiar imperative code, standard Java `HttpClient`, and simple stack trace debugging. |
| **Reactive / Non-Blocking** | Spring WebFlux, Project Reactor, Netty `WebClient` | Event-loop based, high-throughput non-blocking executions with minimal thread overhead. |

## 🏗️ System Architecture

### 1. Ingestion & Parsing Strategy Pipeline
1. **Multi-Source Upload (`POST /api/v1/parse`):** Users upload a browser `.har` trace, Swagger/OpenAPI URL or file, or API collection payload.
2. **Strategy Resolution:** The system delegates parsing to the appropriate concrete strategy implementation (`HarParserStrategy`, `SwaggerParserStrategy`, etc.) via a Spring-managed Strategy/Factory pattern.
3. **Blueprint Normalization & Export:** Strips browser noise, parameterizes path parameters, sanitizes headers, and outputs a normalized `StressConfig` preview that can be exported directly as a `stress.yaml` file (`POST /api/v1/parse/export`).

### 2. Execution & Real-Time Streaming Architecture
*   **Dynamic Engine Dispatch:** The backend inspects the target engine parameter passed in the UI payload (`VIRTUAL_THREADS` or `REACTIVE`) and dispatches the execution task to the corresponding execution service implementation.
*   **Thread-Safe Metrics Collector:** Logs response status codes and sub-millisecond execution times (`System.nanoTime()`) into thread-safe atomic accumulators during active test runs.
*   **SSE Emitter & Dashboard:** Pushes real-time metric snapshots over SSE (`/api/v1/test/stream/{testId}`) to render latency percentiles and throughput charts in Chart.js.

## 📜 The `stress.yaml` Blueprint

The `stress.yaml` file acts as the declarative blueprint for load tests. The Spring Boot application parses this file at runtime (using Jackson YAML) into Java Configuration POJOs (`StressConfig`) to drive execution loops.

**Key Capabilities:**
*   **Engine & Concurrency Settings:** Specifies the target execution engine and concurrency level.
*   **Load Distribution Strategy:** Defines weighted scenario splits across concurrent execution loops.
*   **Runtime Variable Injection:** Resolves placeholder syntax (e.g., `${AUTH_TOKEN}`, `${random.uuid}`) prior to request execution.
*   **Threshold Evaluation:** Evaluates percentiles (p50, p90, p95, p99) against rule assertions for CI/CD pipeline pass/fail status.

## 🚀 Getting Started

### Prerequisites
* Java 21+
* Maven

### How to Run
1. Start the Spring Boot application locally:
   ```bash
   ./mvnw spring-boot:run
   ```
2. Open your browser and navigate to the Control Center:
   ```text
   http://localhost:8080/index.html
   ```
3. Drag and drop any `.har` file, Swagger spec, or API collection into the drop zone.
4. Select your preferred execution engine (**Virtual Threads** or **Reactive WebFlux**), tweak routes/concurrency on the dashboard, and click **Launch Stress Test** or **Export stress.yaml**.

## 🧪 Recommended Experiment Plan

1.  **Baseline Test (Low Load):** 10 concurrent requests for 15 seconds. Ensure ~10–15ms average latency with 0% error rate.
2.  **Concurrency Load Test (Medium Load):** 200 virtual threads/users. Measure thread contention, HikariCP database connection pool wait times, and p95/p99 tail latency spikes.
3.  **Breaking Point / Spike Test (High Load):** 1,000+ concurrent connections. Observe `HTTP 503 Service Unavailable` or `SocketTimeoutException` errors as the target Spring app exhausts resource pools.


## 📊 Repository Traffic
<!-- CLONE_STATS_START -->
**All-Time Clones:** 13 | **All-Time Unique Cloners:** 12 *(Last Updated: 2026-08-21 10:37 UTC)*
<!-- CLONE_STATS_END -->

---
[Explore this project in Gemini Notebook](https://notebook.google.com/notebook/b6806e2b-7226-4b8a-abf7-9687dd9aba4f)

*Developed and maintained by Stavros Nicolaou | Larnaca, Cyprus*

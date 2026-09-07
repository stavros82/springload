CI: Enabling Testcontainers for isolated DB tests

Overview

The project includes an integration test that uses Testcontainers to start ephemeral PostgreSQL databases per flow. To avoid adding Testcontainers to local development classpath, Testcontainers dependencies are enabled via a Maven profile.

Maven

1. Activate the profile in CI to include Testcontainers dependencies and run the integration tests that require Docker:

   mvn -Dci-containers=true test

   Or with explicit profile activation:

   mvn -Pci-containers test

2. The integration test (src/test/java/com/springload/integration/IsolatedFlowIT.java) is guarded and will be skipped automatically when Docker is not available. When the profile is active and Docker is available, Testcontainers will start ephemeral Postgres containers for isolation.

Gradle (example)

If you use Gradle, add Testcontainers to your test dependencies and guard integration tests similarly. Example (build.gradle.kts):

dependencies {
    testImplementation("org.testcontainers:testcontainers:1.19.0")
    testImplementation("org.testcontainers:postgresql:1.19.0")
}

tasks.test {
    // Skip integration tests unless CI_ENABLE_CONTAINERS=true
    val enableContainers: String? by project
    if (enableContainers != "true") {
        exclude("**/IsolatedFlowIT.class")
    }
}

CI guidance

- Ensure Docker is available on the CI runner and the Docker daemon can be started.
- Use the Maven property `-Dci-containers=true` when running tests in CI to include Testcontainers.
- The Testcontainers library will automatically download images and manage lifecycle. Consider caching images in CI for faster runs.

Usage pattern for flow-runner

- In test harnesses or CI pipelines that start flow runs in parallel, call the FlowDbTestUtil.startEphemeralDb() to obtain a JDBC URL/credentials for each flow, inject those into the flow configuration, and close the FlowDb when the flow completes.

Questions or next steps

- Add a CI job example (GitHub Actions) that runs the tests with the profile enabled.

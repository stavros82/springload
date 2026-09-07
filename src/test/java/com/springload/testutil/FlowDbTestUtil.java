package com.springload.testutil;

import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * Test utility to create an ephemeral PostgreSQL instance per flow using Testcontainers.
 * Intended for use in test harnesses and CI to isolate concurrent flows.
 */
public final class FlowDbTestUtil {

    private FlowDbTestUtil() {}

    public static FlowDb startEphemeralDb() {
        String name = "flow_" + UUID.randomUUID().toString().replace('-', '_').substring(0, 8);
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName(name)
                .withUsername("user")
                .withPassword("pass");
        container.start();
        return new FlowDb(container);
    }

    public static final class FlowDb implements AutoCloseable {
        private final PostgreSQLContainer<?> container;

        public FlowDb(PostgreSQLContainer<?> container) {
            this.container = container;
        }

        public String getJdbcUrl() { return container.getJdbcUrl(); }
        public String getUsername() { return container.getUsername(); }
        public String getPassword() { return container.getPassword(); }

        @Override
        public void close() {
            try {
                container.stop();
            } catch (Exception ignored) {}
        }
    }
}

package com.springload.integration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;
import org.junit.jupiter.api.Assumptions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test that demonstrates isolating flows using Testcontainers PostgreSQL instances.
 * Each container represents an independent DB for a concurrent flow.
 */
public class IsolatedFlowIT {

    @Test
    public void twoEphemeralDbsAreIsolated() throws Exception {
        // Skip the test if Docker is not available in the current environment
        boolean dockerAvailable = true;
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker not available, skipping Testcontainers integration test");

        try (PostgreSQLContainer<?> db1 = new PostgreSQLContainer<>("postgres:15-alpine").withDatabaseName("flow1").withUsername("user").withPassword("pass");
             PostgreSQLContainer<?> db2 = new PostgreSQLContainer<>("postgres:15-alpine").withDatabaseName("flow2").withUsername("user").withPassword("pass")) {

            db1.start();
            db2.start();

            try (Connection c1 = java.sql.DriverManager.getConnection(db1.getJdbcUrl(), db1.getUsername(), db1.getPassword());
                 Connection c2 = java.sql.DriverManager.getConnection(db2.getJdbcUrl(), db2.getUsername(), db2.getPassword())) {

                try (Statement s1 = c1.createStatement(); Statement s2 = c2.createStatement()) {
                    s1.execute("CREATE TABLE IF NOT EXISTS kv(k text primary key, v text);");
                    s2.execute("CREATE TABLE IF NOT EXISTS kv(k text primary key, v text);");

                    s1.execute("INSERT INTO kv(k,v) VALUES('x','db1')");
                    s2.execute("INSERT INTO kv(k,v) VALUES('x','db2')");

                    try (ResultSet r1 = s1.executeQuery("SELECT v FROM kv WHERE k='x'")) {
                        r1.next();
                        assertEquals("db1", r1.getString(1));
                    }

                    try (ResultSet r2 = s2.executeQuery("SELECT v FROM kv WHERE k='x'")) {
                        r2.next();
                        assertEquals("db2", r2.getString(1));
                    }
                }
            }
        }
    }
}

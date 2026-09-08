package com.springload.util;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.net.http.HttpHeaders;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DynamicVariableResolverTest {

    @Test
    void bypassesStringsWithoutPlaceholders() {
        assertEquals("/api/vets/101", DynamicVariableResolver.resolve("/api/vets/101"));
        assertNull(DynamicVariableResolver.resolve(null));
    }

    @Test
    void resolvesRandomRangeWithinBounds() {
        String template = "/api/items/${random(1-10)}";
        Pattern digitPattern = Pattern.compile("/api/items/(\\d+)");
        for (int i = 0; i < 50; i++) {
            String resolved = DynamicVariableResolver.resolve(template);
            var matcher = digitPattern.matcher(resolved);
            assertTrue(matcher.matches());
            int value = Integer.parseInt(matcher.group(1));
            assertTrue(value >= 1 && value <= 10);
        }
    }

    @Test
    void resolvesUuidAndTimestamp() {
        String uuidResult = DynamicVariableResolver.resolve("${random.uuid}");
        assertNotNull(uuidResult);
        assertFalse(uuidResult.contains("${"));

        String tsResult = DynamicVariableResolver.resolve("ts=${timestamp}");
        assertTrue(tsResult.startsWith("ts="));
        assertTrue(Long.parseLong(tsResult.substring(3)) > 0);
    }

    @Test
    void resolvesHeaderMapValues() {
        Map<String, String> headers = Map.of("X-Request-Id", "${random.uuid}");
        Map<String, String> resolved = DynamicVariableResolver.resolveHeaders(headers);
        assertNotEquals(headers.get("X-Request-Id"), resolved.get("X-Request-Id"));
    }

    @Test
    void detectsCorrelatedVariables() {
        assertTrue(DynamicVariableResolver.hasCorrelatedVariables("{\"petId\": \"${pet_id}\"}"));
        assertTrue(DynamicVariableResolver.hasCorrelatedVariables("/api/owners/${owner_id}/pets"));
        assertFalse(DynamicVariableResolver.hasCorrelatedVariables("/api/owners/${random(1-100)}/pets"));
        assertFalse(DynamicVariableResolver.hasCorrelatedVariables("/api/items/${random.uuid}"));
        assertFalse(DynamicVariableResolver.hasCorrelatedVariables("ts=${timestamp}"));
        assertFalse(DynamicVariableResolver.hasCorrelatedVariables("/api/vets/101"));
        assertFalse(DynamicVariableResolver.hasCorrelatedVariables(null));
    }

    @Test
    void resolvesExtractedVariablesFromResponse() {
        Map<String, String> extracted = DynamicVariableResolver.extract(
                Map.of("customerId", "$.id"),
                "{\"id\":42}",
                HttpHeaders.of(Map.of(), (name, value) -> true));

        assertEquals("42", extracted.get("customerId"));
        assertEquals("/customers/42",
                DynamicVariableResolver.resolve("/customers/${customerId}", extracted));
    }
}

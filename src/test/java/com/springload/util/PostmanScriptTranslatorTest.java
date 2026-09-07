package com.springload.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostmanScriptTranslatorTest {

    @Test
    void translatesJsonPathExtraction() {
        List<String> script = List.of(
            "pm.environment.set(\"userId\", pm.response.json().id);"
        );

        Map<String, String> result = PostmanScriptTranslator.translate(script);

        assertEquals(1, result.size());
        assertEquals("$.id", result.get("userId"));
    }

    @Test
    void translatesHeaderExtraction() {
        List<String> script = List.of(
            "pm.globals.set(\"token\", pm.response.headers.get(\"X-Auth-Token\"));"
        );

        Map<String, String> result = PostmanScriptTranslator.translate(script);

        assertEquals(1, result.size());
        assertEquals("header:X-Auth-Token", result.get("token"));
    }

    @Test
    void translatesMultipleExtractions() {
        List<String> script = List.of(
            "pm.environment.set(\"user_id\", pm.response.json().user.id);",
            "pm.environment.set(\"auth_token\", pm.response.headers.get(\"Authorization\"));"
        );

        Map<String, String> result = PostmanScriptTranslator.translate(script);

        assertEquals(2, result.size());
        assertEquals("$.user.id", result.get("user_id"));
        assertEquals("header:Authorization", result.get("auth_token"));
    }

    @Test
    void ignoresCommentLines() {
        List<String> script = List.of(
            "// Extract the user ID",
            "pm.environment.set(\"userId\", pm.response.json().id);",
            "/* Block comment */ pm.globals.set(\"token\", pm.response.headers.get(\"X-Token\"));"
        );

        Map<String, String> result = PostmanScriptTranslator.translate(script);

        assertEquals(1, result.size());
        assertEquals("$.id", result.get("userId"));
    }

    @Test
    void handlesVariablesWithDifferentScopes() {
        List<String> script = List.of(
            "pm.environment.set(\"env_var\", pm.response.json().data);",
            "pm.globals.set(\"global_var\", pm.response.json().meta);",
            "pm.variables.set(\"local_var\", pm.response.json().local);"
        );

        Map<String, String> result = PostmanScriptTranslator.translate(script);

        assertEquals(3, result.size());
        assertEquals("$.data", result.get("env_var"));
        assertEquals("$.meta", result.get("global_var"));
        assertEquals("$.local", result.get("local_var"));
    }

    @Test
    void ignoresUnrecognizedExpressions() {
        List<String> script = List.of(
            "pm.environment.set(\"var1\", 42);",
            "pm.environment.set(\"var2\", \"literal string\");",
            "pm.environment.set(\"var3\", pm.response.json().id);"
        );

        Map<String, String> result = PostmanScriptTranslator.translate(script);

        // Only var3 should be extracted (recognized expression)
        assertEquals(1, result.size());
        assertEquals("$.id", result.get("var3"));
    }

    @Test
    void handlesJsonPathWithBracketNotation() {
        List<String> script = List.of(
            "pm.environment.set(\"item\", pm.response.json()[\"data\"]);"
        );

        Map<String, String> result = PostmanScriptTranslator.translate(script);

        assertEquals(1, result.size());
        assertEquals("$.data", result.get("item"));
    }

    @Test
    void handlesRootJsonPath() {
        List<String> script = List.of(
            "pm.environment.set(\"response\", pm.response.json());"
        );

        Map<String, String> result = PostmanScriptTranslator.translate(script);

        assertEquals(1, result.size());
        assertEquals("$", result.get("response"));
    }

    @Test
    void balancesParenthesesCorrectly() {
        List<String> script = List.of(
            "pm.environment.set(\"userId\", pm.response.json().user.id));"
        );

        Map<String, String> result = PostmanScriptTranslator.translate(script);

        assertEquals(1, result.size());
        assertEquals("$.user.id", result.get("userId"));
    }

    @Test
    void ignoresEmptyAndNullInput() {
        Map<String, String> result = PostmanScriptTranslator.translate(null);
        assertTrue(result.isEmpty());

        result = PostmanScriptTranslator.translate(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void ignoresEmptyLines() {
        List<String> script = List.of(
            "",
            "   ",
            "pm.environment.set(\"id\", pm.response.json().id);",
            ""
        );

        Map<String, String> result = PostmanScriptTranslator.translate(script);

        assertEquals(1, result.size());
        assertEquals("$.id", result.get("id"));
    }

    @Test
    void handlesWhitespaceInVariableNames() {
        List<String> script = List.of(
            "pm.environment.set( \"userId\" , pm.response.json().id );"
        );

        Map<String, String> result = PostmanScriptTranslator.translate(script);

        assertEquals(1, result.size());
        assertEquals("$.id", result.get("userId"));
    }
}

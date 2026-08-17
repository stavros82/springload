package com.springload.controller;

import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class EngineInfoController {

    private final Environment environment;

    public EngineInfoController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/engine")
    public ResponseEntity<Map<String, String>> getActiveEngine() {
        String[] activeProfiles = environment.getActiveProfiles();
        
        String engineName = "Virtual Threads (Default)";
        if (Arrays.asList(activeProfiles).contains("reactive")) {
            engineName = "Spring WebFlux (Reactive)";
        } else if (Arrays.asList(activeProfiles).contains("virtual-thread")) {
            engineName = "Java Virtual Threads";
        }

        return ResponseEntity.ok(Map.of("engine", engineName));
    }
}
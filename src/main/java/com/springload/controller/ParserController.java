package com.springload.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.springload.dto.StressConfig;
import com.springload.service.ParserFactory;
import com.springload.strategy.ParserType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/parse")
public class ParserController {

    private final ParserFactory parserFactory;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public ParserController(ParserFactory parserFactory) {
        this.parserFactory = parserFactory;
    }

    // Upload & Parse (POST /api/v1/parse)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StressConfig> parseFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") ParserType type) throws IOException {
        
        StressConfig config = parserFactory.getStrategy(type).parse(file.getInputStream());
        return ResponseEntity.ok(config);
    }

    // Export generated stress.yaml
    @PostMapping(value = "/export", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/x-yaml")
    public ResponseEntity<String> exportYaml(@RequestBody StressConfig config) throws IOException {
        String yamlText = yamlMapper.writeValueAsString(config);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"stress.yaml\"")
                .body(yamlText);
    }
}
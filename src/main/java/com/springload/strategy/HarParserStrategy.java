package com.springload.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springload.dto.ExecutionSettings;
import com.springload.dto.HarInputDto;
import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.Thresholds;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.util.*;

@Component
public class HarParserStrategy implements StressConfigParserStrategy {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public ParserType getType() {
        return ParserType.HAR;
    }

    @Override
    public StressConfig parse(InputStream inputStream) {
        try {
            HarInputDto har = mapper.readValue(inputStream, HarInputDto.class);
            List<ScenarioConfig> scenarios = new ArrayList<>();
            String baseUrl = "";

            if (har.log() != null && har.log().entries() != null) {
                for (HarInputDto.Entry entry : har.log().entries()) {
                    HarInputDto.Request req = entry.request();
                    if (req == null || req.url() == null) continue;

                    // Filter static assets
                    if (req.url().matches(".*\\.(css|js|png|jpg|jpeg|gif|svg|woff2|ico)$")) continue;

                    URI uri = new URI(req.url());
                    if (baseUrl.isEmpty()) {
                        baseUrl = uri.getScheme() + "://" + uri.getAuthority();
                    }

                    Map<String, String> headers = new HashMap<>();
                    if (req.headers() != null) {
                        req.headers().forEach(h -> {
                            if (!h.name().startsWith(":")) {
                                headers.put(h.name(), h.value());
                            }
                        });
                    }

                    Map<String, String> params = new HashMap<>();
                    if (req.queryString() != null) {
                        req.queryString().forEach(q -> params.put(q.name(), q.value()));
                    }

                    scenarios.add(new ScenarioConfig(
                        req.method() + " " + uri.getPath(),
                        req.method(),
                        uri.getPath(),
                        1,
                        headers,
                        params,
                        req.postData() != null ? req.postData().text() : null,
                        true
                    ));
                }
            }

            return new StressConfig(
                "Imported HAR Blueprint",
                baseUrl,
                new ExecutionSettings(50, 30, 5),
                scenarios,
                new Thresholds(200, 500, 1.0)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse HAR input", e);
        }
    }
}
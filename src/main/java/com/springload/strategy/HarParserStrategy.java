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

                    if (isStaticAsset(req.url())) {
                        continue;
                    }

                    URI uri = new URI(req.url());
                    baseUrl = extractBaseUrlIfEmpty(baseUrl, req.url());
                    scenarios.add(mapToScenario(req, uri));
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

    private boolean isStaticAsset(String url) {
        return url.matches(".*\\.(css|js|png|jpg|jpeg|gif|svg|woff|woff2|ico|ttf|eot)(\\?.*)?$");
    }

    private String extractBaseUrlIfEmpty(String currentBaseUrl, String requestUrl) {
        if (currentBaseUrl == null || currentBaseUrl.isEmpty()) {
            try {
                URI uri = new URI(requestUrl);
                return uri.getScheme() + "://" + uri.getAuthority();
            } catch (Exception e) {
                return currentBaseUrl;
            }
        }
        return currentBaseUrl;
    }

    private ScenarioConfig mapToScenario(HarInputDto.Request req, URI uri) {
        Map<String, String> headers = extractSanitizedHeaders(req);

        Map<String, String> params = new HashMap<>();
        if (req.queryString() != null) {
            req.queryString().forEach(q -> params.put(q.name(), q.value()));
        }

        return new ScenarioConfig(
                req.method() + " " + uri.getPath(),
                req.method(),
                uri.getPath(),
                1,
                headers,
                params,
                req.postData() != null ? req.postData().text() : null,
                true
        );
    }

    private Map<String, String> extractSanitizedHeaders(HarInputDto.Request req) {
        Map<String, String> headers = new HashMap<>();
        if (req.headers() != null) {
            req.headers().forEach(h -> {
                String name = h.name();
                if (name != null && !name.startsWith(":") && !isRestrictedHeader(name)) {
                    headers.put(name, h.value());
                }
            });
        }
        return headers;
    }

    /**
     * Checks if a header name is restricted by Java's HttpClient.
     * Transport-level headers like Connection, Content-Length, Host, etc.,
     * must be managed automatically by the HTTP engine.
     */
    private boolean isRestrictedHeader(String headerName) {
        if (headerName == null) {
            return true;
        }
        String lower = headerName.trim().toLowerCase();
        return lower.equals("connection") ||
                lower.equals("content-length") ||
                lower.equals("host") ||
                lower.equals("upgrade") ||
                lower.equals("expect") ||
                lower.equals("transfer-encoding");
    }
}
package com.springload.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HarInputDto(Log log) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Log(List<Entry> entries) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(Request request, Response response, Boolean active) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(
            String method,
            String url,
            List<Header> headers,
            List<QueryParam> queryString,
            PostData postData
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(int status, String mimeType) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Header(String name, String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryParam(String name, String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PostData(String mimeType, String text) {}
}
package com.monitoring.dashboard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

public class NodeApiService {

    private static final String BASE_URL = "http://localhost:6789/api";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public NodeApiService() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public List<NodeDto> getAllNodes() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/nodes"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new IOException("Failed to load nodes. HTTP status: " + response.statusCode());
        }

        return objectMapper.readValue(
                response.body(),
                new TypeReference<List<NodeDto>>() {}
        );
    }

    public List<FailureReportDto> getFailureReports() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/failure-reports"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new IOException("Failed to load failure reports. HTTP status: " + response.statusCode());
        }

        return objectMapper.readValue(
                response.body(),
                new TypeReference<List<FailureReportDto>>() {}
        );
    }
}
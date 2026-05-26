package com.monitoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.net.URI;

public class RestClient {

    private static final HttpClient client = HttpClient.newHttpClient();

    private static final ObjectMapper mapper = new ObjectMapper();

    public static String post(String url, Object body) {

        try {
            String json = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(
                        "POST " + url + " failed with status "
                                + response.statusCode()
                                + ": "
                                + response.body());
            }

            return response.body();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

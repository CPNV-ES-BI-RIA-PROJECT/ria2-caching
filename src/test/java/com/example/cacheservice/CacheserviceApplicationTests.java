package com.example.cacheservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "cache.store.type=in-memory")
class CacheserviceApplicationTests {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void contextLoads() {
    }

    @Test
    void cacheLifecycleWorksFromMissToReady() throws Exception {
        HttpResponse<String> missResponse = get("/v1/cache/extract/job-123");
        assertEquals(404, missResponse.statusCode());
        assertJsonField(missResponse, "status", "MISS");

        HttpResponse<String> lockResponse = post(
                "/v1/cache/extract/job-123/lock",
                """
                        {
                          "owner": "orchestrator",
                          "leaseMs": 300000
                        }
                        """);
        assertEquals(200, lockResponse.statusCode());
        assertJsonField(lockResponse, "owner", "orchestrator");
        String token = readBody(lockResponse).get("token").asString();
        assertNotNull(token);

        HttpResponse<String> computingResponse = get("/v1/cache/extract/job-123");
        assertEquals(409, computingResponse.statusCode());
        assertJsonField(computingResponse, "status", "COMPUTING");
        assertJsonField(computingResponse, "owner", "orchestrator");

        HttpResponse<String> secondLockResponse = post(
                "/v1/cache/extract/job-123/lock",
                """
                        {
                          "owner": "orchestrator",
                          "leaseMs": 300000
                        }
                        """);
        assertEquals(409, secondLockResponse.statusCode());

        HttpResponse<String> invalidPublishResponse = post(
                "/v1/cache/extract/job-123/publish",
                """
                        {
                          "token": "wrong-token",
                          "artifactUri": "s3://bucket/extract/job-123.parquet",
                          "metadata": { "rows": 42 },
                          "ttlSeconds": 86400
                        }
                        """);
        assertEquals(403, invalidPublishResponse.statusCode());

        HttpResponse<String> publishResponse = post(
                "/v1/cache/extract/job-123/publish",
                """
                        {
                          "token": "%s",
                          "artifactUri": "s3://bucket/extract/job-123.parquet",
                          "metadata": { "rows": 42 },
                          "ttlSeconds": 86400
                        }
                        """.formatted(token));
        assertEquals(200, publishResponse.statusCode());
        assertJsonField(publishResponse, "status", "READY");
        assertJsonField(publishResponse, "artifactUri", "s3://bucket/extract/job-123.parquet");
        assertEquals(42, readBody(publishResponse).path("metadata").path("rows").asInt());

        HttpResponse<String> readyResponse = get("/v1/cache/extract/job-123");
        assertEquals(200, readyResponse.statusCode());
        assertJsonField(readyResponse, "status", "READY");

        HttpResponse<String> deleteResponse = delete("/v1/cache/extract/job-123");
        assertEquals(204, deleteResponse.statusCode());

        HttpResponse<String> afterDeleteResponse = get("/v1/cache/extract/job-123");
        assertEquals(404, afterDeleteResponse.statusCode());
        assertJsonField(afterDeleteResponse, "status", "MISS");
    }

    @Test
    void publishWithoutAnActiveLockReturnsConflict() throws Exception {
        HttpResponse<String> response = post(
                "/v1/cache/transform/job-456/publish",
                """
                        {
                          "token": "missing-lock",
                          "artifactUri": "s3://bucket/transform/job-456.parquet",
                          "metadata": { "rows": 10 },
                          "ttlSeconds": 120
                        }
                        """);

        assertEquals(409, response.statusCode());
        assertJsonField(response, "message", "The lock no longer exists. The computation must be retried.");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .DELETE()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void assertJsonField(HttpResponse<String> response, String field, String expected) throws Exception {
        assertEquals(expected, readBody(response).path(field).asString());
    }

    private JsonNode readBody(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }
}

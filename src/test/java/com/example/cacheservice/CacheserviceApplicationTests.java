package com.example.cacheservice;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void getMissStartsLockWorkflowAndThenPublishMakesEntryReady() throws Exception {
        HttpResponse<String> missResponse = get("/v1/cache/extract/job-123");
        assertEquals(404, missResponse.statusCode());
        assertJsonField(missResponse, "status", "MISS");

        HttpResponse<String> computingResponse = get("/v1/cache/extract/job-123");
        assertEquals(409, computingResponse.statusCode());
        assertJsonField(computingResponse, "status", "COMPUTING");

        HttpResponse<String> manualLockResponse = post("/v1/cache/extract/job-123/lock");
        assertEquals(409, manualLockResponse.statusCode());

        HttpResponse<String> publishResponse = post("/v1/cache/extract/job-123/publish");
        assertEquals(200, publishResponse.statusCode());
        assertJsonField(publishResponse, "status", "READY");

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
    void manualLockEndpointStaysAvailableForDirectUse() throws Exception {
        HttpResponse<String> lockResponse = post("/v1/cache/manual/job-789/lock");
        assertEquals(200, lockResponse.statusCode());
        assertJsonField(lockResponse, "status", "COMPUTING");

        HttpResponse<String> publishResponse = post("/v1/cache/manual/job-789/publish");
        assertEquals(200, publishResponse.statusCode());
        assertJsonField(publishResponse, "status", "READY");
    }

    @Test
    void publishWithoutAnActiveLockReturnsConflict() throws Exception {
        HttpResponse<String> response = post("/v1/cache/transform/job-456/publish");

        assertEquals(409, response.statusCode());
        assertJsonField(response, "message", "The lock no longer exists. The computation must be retried.");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .POST(HttpRequest.BodyPublishers.noBody())
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

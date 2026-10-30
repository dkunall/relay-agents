package com.relayagents.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * A thin HTTP client that never throws on status codes, so tests can assert 4xx responses as easily as 2xx.
 */
public final class Api {

    public record Response(int status, JsonNode body, HttpHeaders headers) {

        public Response expect(int expected) {
            if (status != expected) {
                throw new AssertionError("expected HTTP " + expected + " but got " + status + ": " + body);
            }
            return this;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final RestClient client;
    private final String token;

    private Api(RestClient client, String token) {
        this.client = client;
        this.token = token;
    }

    public static Api at(int port) {
        return new Api(RestClient.builder().baseUrl("http://localhost:" + port).build(), null);
    }

    public Api as(String bearer) {
        return new Api(client, bearer);
    }

    public Response get(String path) {
        return send(HttpMethod.GET, path, null, Map.of());
    }

    public Response post(String path, Object body) {
        return send(HttpMethod.POST, path, body, Map.of());
    }

    public Response put(String path, Object body) {
        return send(HttpMethod.PUT, path, body, Map.of());
    }

    public Response send(HttpMethod method, String path, Object body, Map<String, String> headers) {
        RestClient.RequestBodySpec spec = client.method(method).uri(path).headers(h -> {
            if (token != null) {
                h.setBearerAuth(token);
            }
            headers.forEach(h::set);
        });
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body instanceof String s ? s : write(body));
        }
        return spec.exchange((request, response) -> {
            HttpStatusCode status = response.getStatusCode();
            byte[] bytes = response.getBody().readAllBytes();
            JsonNode json = bytes.length == 0 ? MAPPER.nullNode() : parse(bytes);
            return new Response(status.value(), json, response.getHeaders());
        });
    }

    public static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static JsonNode parse(byte[] bytes) {
        try {
            return MAPPER.readTree(bytes);
        } catch (IOException e) {
            return MAPPER.getNodeFactory().textNode(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String write(Object body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}

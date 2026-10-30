package com.relayagents.tool.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayagents.secret.application.SecretResolver;
import com.relayagents.shared.json.Json;
import com.relayagents.tool.application.HttpToolDefinition;
import com.relayagents.tool.application.HttpToolFactory;
import com.relayagents.tool.application.Tool;
import com.relayagents.tool.application.ToolDescriptor;
import com.relayagents.tool.application.ToolException;
import com.relayagents.tool.application.ToolInvocation;
import com.relayagents.tool.application.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns a registered definition into a callable tool with the guard rails every tenant tool gets:
 * host allowlist checked on the final URL, no redirects (a redirect could leave the allowlist),
 * a response size cap, and secret header values that never reach the model or the journal.
 */
@Component
class SandboxedHttpToolFactory implements HttpToolFactory {

    private static final Pattern URL_PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]*)}");
    private static final Pattern SECRET_REF = Pattern.compile("\\{\\{\\s*secret\\.([A-Z][A-Z0-9_]*)\\s*}}");

    private final HttpToolProperties properties;
    private final SecretResolver secrets;
    private final Json json;
    private final HttpClient client;

    SandboxedHttpToolFactory(HttpToolProperties properties, SecretResolver secrets, Json json) {
        this.properties = properties;
        this.secrets = secrets;
        this.json = json;
        this.client = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public void check(HttpToolDefinition definition) {
        if (!Set.of("GET", "POST").contains(definition.method())) {
            throw new IllegalArgumentException("method must be GET or POST");
        }
        String template = definition.urlTemplate();
        int schemeEnd = template.indexOf("://");
        if (schemeEnd < 0) {
            throw new IllegalArgumentException("urlTemplate must be an absolute http(s) URL");
        }
        int authorityEnd = template.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int at = template.indexOf(c, schemeEnd + 3);
            if (at >= 0) {
                authorityEnd = Math.min(authorityEnd, at);
            }
        }
        if (template.substring(0, authorityEnd).contains("{")) {
            throw new IllegalArgumentException("Placeholders are not allowed in the scheme or host");
        }
        URI probe;
        try {
            probe = URI.create(URL_PLACEHOLDER.matcher(template).replaceAll("x"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("urlTemplate is not a valid URL");
        }
        checkTarget(probe);
    }

    private void checkTarget(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http and https URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Credentials in URLs are not allowed");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!properties.allowedHosts().contains(host)) {
            throw new IllegalArgumentException("Host '" + host + "' is not in relay.tools.http.allowed-hosts");
        }
    }

    @Override
    public Tool create(HttpToolDefinition definition) {
        ToolDescriptor descriptor = new ToolDescriptor(definition.name(), definition.description(), definition.schema(),
            definition.scopes(), definition.requiresApproval(), true, "http");
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult invoke(ToolInvocation invocation) {
                return call(definition, invocation);
            }
        };
    }

    private ToolResult call(HttpToolDefinition definition, ToolInvocation invocation) {
        JsonNode args = invocation.arguments();
        URI uri;
        try {
            uri = URI.create(expandUrl(definition.urlTemplate(), args));
            checkTarget(uri);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), false);
        }
        Map<String, String> used = new HashMap<>();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(invocation.timeout())
            .header("Accept", "application/json")
            .header("Idempotency-Key", invocation.idempotencyKey());
        definition.headers().forEach((name, template) -> request.header(name, expandSecrets(template, used)));
        if (definition.method().equals("POST")) {
            request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.write(args)));
        } else {
            request.GET();
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new ToolException("HTTP tool timed out", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted", true, e);
        } catch (IOException e) {
            throw new ToolException("HTTP tool unreachable: " + e.getClass().getSimpleName(), true, e);
        }
        int status = response.statusCode();
        String body = readCapped(response.body());
        if (status >= 300 && status < 400) {
            throw new ToolException("HTTP tool returned a redirect (" + status + "); redirects are not followed", false);
        }
        if (status == 429 || status >= 500) {
            throw new ToolException("HTTP tool returned " + status, true);
        }
        ObjectNode output = json.object().put("status", status);
        try {
            output.set("body", json.read(body));
        } catch (IllegalArgumentException notJson) {
            output.put("body", body);
        }
        return new ToolResult(output, used);
    }

    private String expandUrl(String template, JsonNode args) {
        Matcher matcher = URL_PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            JsonNode value = args.path(matcher.group(1));
            String text = value.isValueNode() ? value.asText() : "";
            matcher.appendReplacement(out, Matcher.quoteReplacement(URLEncoder.encode(text, StandardCharsets.UTF_8)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String expandSecrets(String template, Map<String, String> used) {
        Matcher matcher = SECRET_REF.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = secrets.resolve(name)
                .orElseThrow(() -> new ToolException("Secret " + name + " is not configured", false));
            used.put(name, value);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String readCapped(InputStream body) {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(properties.maxResponseBytes() + 1);
            if (bytes.length > properties.maxResponseBytes()) {
                throw new ToolException("HTTP tool response exceeds " + properties.maxResponseBytes() + " bytes", false);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("HTTP tool response could not be read", true, e);
        }
    }
}

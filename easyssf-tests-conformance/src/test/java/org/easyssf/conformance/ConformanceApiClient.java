package org.easyssf.conformance;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The part of the suite's REST API the tests use: creating plans and modules, starting
 * modules and reading their state and log.
 */
public final class ConformanceApiClient {

    private static final Set<String> TERMINAL_STATES = Set.of("FINISHED", "INTERRUPTED");

    private final URI baseUri;

    private final HttpClient httpClient;

    private final JsonMapper json = JsonMapper.builder().build();

    ConformanceApiClient(URI baseUri, SSLContext sslContext) {
        this.baseUri = baseUri;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).sslContext(sslContext).build();
    }

    public void waitUntilAvailable(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = send(request("/api/runner/available").GET().build(), true);
                if (response.statusCode() == 200) {
                    return;
                }
                lastFailure = new IllegalStateException("The conformance suite returned HTTP " + response.statusCode());
            }
            catch (RuntimeException ex) {
                lastFailure = ex;
            }
            sleep(Duration.ofSeconds(2));
        }
        throw new IllegalStateException("The conformance suite did not become available at " + this.baseUri,
                lastFailure);
    }

    /**
     * Creates a plan and returns its id together with the modules it consists of.
     */
    public Plan createPlan(String planName, Map<String, String> variant, JsonNode config) {
        String path = "/api/plan?planName=" + encode(planName) + "&variant=" + encode(json.writeValueAsString(variant));
        HttpRequest request = request(path).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(config.toString()))
            .build();
        JsonNode plan = expectJson(request, 201, true);
        String id = requiredText(plan, "id");
        List<PlanModule> modules = plan.path("modules")
            .valueStream()
            .map((module) -> new PlanModule(requiredText(module, "testModule"), stringMap(module.path("variant"))))
            .toList();
        return new Plan(id, planName, variant, modules, this.baseUri.resolve("/plan-detail.html?plan=" + id));
    }

    /**
     * Creates and starts a module of a plan. Returns once the module waits for the
     * receiver under test or has finished already.
     */
    public String startModule(Plan plan, PlanModule module) {
        String path = "/api/runner?test=" + encode(module.name()) + "&plan=" + encode(plan.id()) + "&variant="
                + encode(json.writeValueAsString(module.variant()));
        JsonNode created = expectJson(request(path).POST(HttpRequest.BodyPublishers.noBody()).build(), 201, true);
        String id = requiredText(created, "id");
        JsonNode info = waitForState(id, Set.of("CONFIGURED", "WAITING"), Duration.ofMinutes(2));
        if ("CONFIGURED".equals(info.path("status").asString())) {
            // not replayed, as a lost response would start the module a second time
            expectJson(request("/api/runner/" + id).POST(HttpRequest.BodyPublishers.noBody()).build(), 200, false);
            waitForState(id, Set.of("WAITING"), Duration.ofMinutes(2));
        }
        return id;
    }

    public ConformanceModuleResult awaitResult(Plan plan, PlanModule module, String moduleId, Duration timeout) {
        JsonNode info = waitForState(moduleId, Set.of(), timeout);
        JsonNode logs = expectJson(request("/api/log/" + moduleId).GET().build(), 200, true);
        return new ConformanceModuleResult(plan, module, moduleId, info.path("status").asString(),
                info.path("result").asString("UNKNOWN"), logs,
                this.baseUri.resolve("/log-detail.html?log=" + moduleId));
    }

    public JsonNode info(String moduleId) {
        return expectJson(request("/api/info/" + moduleId).GET().build(), 200, true);
    }

    /**
     * Waits until the module is in one of the states or in a terminal one.
     */
    private JsonNode waitForState(String moduleId, Set<String> states, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode lastInfo = null;
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                lastInfo = info(moduleId);
                String status = lastInfo.path("status").asString();
                if (states.contains(status) || TERMINAL_STATES.contains(status)) {
                    return lastInfo;
                }
            }
            catch (RuntimeException ex) {
                lastFailure = ex;
            }
            sleep(Duration.ofSeconds(1));
        }
        throw new IllegalStateException(
                "Timed out waiting for module " + moduleId + " to reach " + states + "; last info: " + lastInfo,
                lastFailure);
    }

    private JsonNode expectJson(HttpRequest request, int expectedStatus, boolean replayable) {
        HttpResponse<String> response = send(request, replayable);
        if (response.statusCode() != expectedStatus) {
            throw new IllegalStateException("Conformance suite API " + request.method() + " " + request.uri()
                    + " returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return this.json.readTree(response.body());
    }

    /**
     * The suite occasionally drops a connection, so transient failures of replayable
     * requests are retried.
     */
    private HttpResponse<String> send(HttpRequest request, boolean replayable) {
        int attempts = replayable ? 4 : 1;
        for (int attempt = 1;; attempt++) {
            try {
                return this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            }
            catch (IOException ex) {
                if (attempt >= attempts) {
                    throw new IllegalStateException("Conformance suite API request failed: " + request.uri(), ex);
                }
                sleep(Duration.ofSeconds(attempt));
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while calling the conformance suite API: " + request.uri(),
                        ex);
            }
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(this.baseUri.resolve(path))
            .timeout(Duration.ofMinutes(2))
            .header("Accept", "application/json");
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        node.properties().forEach((property) -> map.put(property.getKey(), property.getValue().asString()));
        return map;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asString(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Conformance suite API response lacks '" + field + "': " + node);
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /**
     * A created plan.
     */
    public record Plan(String id, String name, Map<String, String> variant, List<PlanModule> modules, URI url) {
    }

    /**
     * A module of a plan with the variant the plan assigns to it.
     */
    public record PlanModule(String name, Map<String, String> variant) {

        @Override
        public String toString() {
            return this.variant.isEmpty() ? this.name : this.name + " " + this.variant;
        }

    }

}

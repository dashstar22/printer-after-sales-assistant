package local.printer.assistant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/** Local upstream used to test the forwarding path. Never reads credentials or opens a client. */
final class ForwardingUpstreamFixture implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpServer server;
    private final HttpServer trap;
    private final ExecutorService workers;
    private final String scenario;
    private final String syntheticKey;
    private final List<String> bodies = new ArrayList<>();
    private final List<String> bodyHashes = new ArrayList<>();
    private int requests;
    private int redirectRequests;
    private boolean authorizationCorrect = true;
    private boolean headersCorrect = true;
    private int active;
    private int maxActive;

    ForwardingUpstreamFixture(String scenario, String syntheticKey) throws IOException {
        if (!ObservationE3Adapter.SCENARIOS.contains(scenario)) throw new IllegalArgumentException("UNKNOWN_FIXTURE");
        this.scenario = scenario;
        this.syntheticKey = syntheticKey;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        trap = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(work -> {
            var thread = new Thread(work, "forwarding-upstream-fixture"); thread.setDaemon(true); return thread;
        });
        server.setExecutor(workers);
        trap.setExecutor(workers);
        trap.createContext("/", exchange -> {
            synchronized (this) { redirectRequests++; }
            exchange.sendResponseHeaders(500, -1); exchange.close();
        });
        server.createContext("/", this::handle);
        trap.start();
        server.start();
    }

    URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"); }

    private void handle(HttpExchange exchange) throws IOException {
        int ordinal;
        byte[] body = exchange.getRequestBody().readNBytes(4097);
        synchronized (this) {
            ordinal = ++requests;
            active++;
            maxActive = Math.max(active, maxActive);
            authorizationCorrect &= ("Bearer " + syntheticKey).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            headersCorrect &= exchange.getRequestHeaders().getFirst("Cookie") == null
                    && exchange.getRequestHeaders().getFirst("Proxy-Authorization") == null
                    && "application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"));
            bodies.add(new String(body, StandardCharsets.UTF_8));
            try { bodyHashes.add(ObservationEvalAdapter.hash(body)); }
            catch (Exception failure) { throw new IllegalStateException("HASH_FAILED"); }
        }
        try {
            if (scenario.equals("disconnect")) return;
            if (scenario.equals("timeout")) Thread.sleep(1500);
            if (scenario.equals("redirect")) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + trap.getAddress().getPort() + "/trap");
                send(exchange, 307, Map.of("error", Map.of("message", "synthetic redirect")));
                return;
            }
            if (scenario.equals("credential-echo")) {
                send(exchange, 401, Map.of("error", Map.of("message", "synthetic echo " + syntheticKey)));
                return;
            }
            if (scenario.startsWith("http-")) {
                send(exchange, Integer.parseInt(scenario.substring(5)), Map.of("error", Map.of("message", "synthetic HTTP failure")));
                return;
            }
            String kind = List.of("FACT", "QUESTION", "UNCLEAR").get((ordinal - 1) % 3);
            boolean malformed = scenario.equals("invalid-json") || scenario.equals("second-sentinel-invalid") && ordinal == 2
                    || scenario.equals("third-sentinel-invalid") && ordinal == 3;
            String text = malformed ? "{broken" : "{\"kind\":\"" + kind + "\"}";
            var message = new LinkedHashMap<String, Object>();
            message.put("role", "assistant"); message.put("content", text);
            if (scenario.equals("tool-response")) message.put("tool_calls", List.of(Map.of("id", "synthetic-tool", "type", "function",
                    "function", Map.of("name", "forbidden", "arguments", "{}"))));
            String finish = scenario.equals("tool-response") ? "tool_calls" : scenario.equals("finish-length") ? "length" : "stop";
            var response = Map.of("id", "forwarding-fixture-" + ordinal, "object", "chat.completion", "model", "loopback-forwarding-fixture",
                    "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish)),
                    "usage", Map.of("prompt_tokens", 100, "completion_tokens", 8, "total_tokens", 108));
            send(exchange, 200, response);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) { active--; }
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] body = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        // Chunked responses are deliberately exercised on normal successes too.
        exchange.sendResponseHeaders(status, status == 200 ? 0 : body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    synchronized Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("requests", requests); result.put("redirect_follow_count", redirectRequests);
        result.put("authorization_correct", authorizationCorrect); result.put("headers_correct", headersCorrect);
        result.put("request_bodies", new ArrayList<>(bodies)); result.put("body_sha256", new ArrayList<>(bodyHashes));
        result.put("active", active); result.put("max_active", maxActive); result.put("real_model_calls", 0);
        return result;
    }

    public void close() {
        server.stop(0); trap.stop(0); workers.shutdownNow();
        try { workers.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}

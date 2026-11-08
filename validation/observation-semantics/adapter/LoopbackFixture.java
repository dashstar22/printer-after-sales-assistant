package local.printer.assistant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Loopback-only synthetic supplier. There is deliberately no forwarding implementation. */
final class LoopbackFixture implements EvaluationEndpoint {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> SCENARIOS = Set.of("success", "invalid-json",
            "second-sentinel-invalid", "third-sentinel-invalid", "tool-response", "finish-length",
            "timeout", "http-401", "http-429", "http-503", "call-budget", "byte-budget",
            "total-byte-budget");
    private final String scenario;
    private final String expectedSystem;
    private final JsonNode expectedSchema;
    private final String model;
    private final Ledger ledger;
    private final HttpServer server;
    private final ExecutorService executor;
    private final List<String> contractErrors = new ArrayList<>();
    private final Map<String, Integer> rejections = new LinkedHashMap<>();
    private final int configuredMaxCalls;
    private final int configuredMaxRequestBytes;
    private final long configuredMaxTotalBytes;
    private volatile RequestState expected;
    private volatile boolean closed;
    private int loopbackRequests;

    LoopbackFixture(String scenario, String expectedSystem, JsonNode expectedSchema, String model,
                    int maxCalls, int maxRequestBytes, long maxTotalBytes) throws IOException {
        if (!SCENARIOS.contains(scenario)) throw new IllegalArgumentException("Unknown fixture scenario");
        if (maxCalls < 0 || maxCalls > 36 || maxRequestBytes < 0 || maxRequestBytes > 4096
                || maxTotalBytes < 0 || maxTotalBytes > 147456)
            throw new IllegalArgumentException("Fixture limits must only shrink the frozen budget");
        this.scenario = scenario;
        this.expectedSystem = Objects.requireNonNull(expectedSystem);
        this.expectedSchema = Objects.requireNonNull(expectedSchema);
        this.model = Objects.requireNonNull(model);
        this.configuredMaxCalls = maxCalls;
        this.configuredMaxRequestBytes = maxRequestBytes;
        this.configuredMaxTotalBytes = maxTotalBytes;
        ledger = new Ledger(maxCalls, maxRequestBytes, maxTotalBytes);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(work -> {
            Thread thread = new Thread(work, "observation-loopback-fixture");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    public void expect(String id, String text) {
        if (closed) throw new IllegalStateException("Fixture is closed");
        expected = new RequestState(Objects.requireNonNull(id), Objects.requireNonNull(text));
    }

    public Map<String, Object> current() {
        RequestState state = expected;
        if (state == null) return Map.of();
        // The SDK can finish reading just before the handler releases its reservation. Wait
        // briefly for that same request only; a timed-out delayed response remains unknown.
        if (state.hasRequests()) {
            try { state.completed.await(200, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        return state.copy();
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>(ledger.snapshot());
        result.put("scenario", scenario);
        result.put("loopback_requests", loopbackRequests);
        result.put("contract_errors", List.copyOf(contractErrors));
        result.put("rejections", new LinkedHashMap<>(rejections));
        result.put("configured_max_calls", configuredMaxCalls);
        result.put("configured_max_request_bytes", configuredMaxRequestBytes);
        result.put("configured_max_total_bytes", configuredMaxTotalBytes);
        result.put("real_upstream_requests", 0);
        result.put("forwarding_implemented", false);
        return result;
    }

    private void handle(HttpExchange exchange) throws IOException {
        // A request captures its case once: a late timed-out response never mutates a later case.
        RequestState state = expected;
        if (state == null) state = new RequestState("UNEXPECTED", "");
        synchronized (this) { loopbackRequests++; }
        int caseRequests = state.incrementRequests();
        boolean reserved = false;
        try {
            byte[] body = exchange.getRequestBody().readNBytes(4097);
            String bodyText = new String(body, StandardCharsets.UTF_8);
            state.put("received_body_bytes", body.length);
            state.put("received_body_bytes_is_lower_bound", body.length == 4097);
            state.put("request_body", bodyText);
            if (body.length == 4097) {
                reject(state, "REQUEST_BYTES_LIMIT", body.length);
                send(exchange, state, 429, errorBody("REQUEST_BYTES_LIMIT"), null);
                return;
            }
            List<String> errors = contract(exchange, bodyText, state.text);
            state.put("request_contract_errors", List.copyOf(errors));
            if (!errors.isEmpty()) {
                synchronized (this) { for (String error : errors) contractErrors.add(state.id + ": " + error); }
                reject(state, "REQUEST_CONTRACT_INVALID", body.length);
                send(exchange, state, 400, errorBody("REQUEST_CONTRACT_INVALID"), null);
                return;
            }
            if (caseRequests > 1) {
                reject(state, "DUPLICATE_CASE_REQUEST", body.length);
                send(exchange, state, 429, errorBody("DUPLICATE_CASE_REQUEST"), null);
                return;
            }
            Reservation reservation = ledger.reserve(body.length);
            if (!reservation.accepted()) {
                reject(state, reservation.reason(), body.length);
                send(exchange, state, 429, errorBody(reservation.reason()), null);
                return;
            }
            reserved = true;
            state.put("supplier_attempts", 1);
            state.put("request_bytes", body.length);
            if ("total-byte-budget".equals(scenario) && reservation.ordinal() == 1)
                ledger.shrinkTotalTo(body.length);
            if ("timeout".equals(scenario)) Thread.sleep(1500);
            if (scenario.startsWith("http-")) {
                send(exchange, state, Integer.parseInt(scenario.substring(5)), errorBody("SYNTHETIC_HTTP_FAILURE"), null);
                return;
            }
            String kind = List.of("FACT", "QUESTION", "UNCLEAR").get((reservation.ordinal() - 1) % 3);
            boolean invalid = "invalid-json".equals(scenario)
                    || "second-sentinel-invalid".equals(scenario) && reservation.ordinal() == 2
                    || "third-sentinel-invalid".equals(scenario) && reservation.ordinal() == 3;
            String content = invalid ? "{broken" : "{\"kind\":\"" + kind + "\"}";
            boolean tools = "tool-response".equals(scenario);
            boolean length = "finish-length".equals(scenario);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", content);
            if (tools) message.put("tool_calls", List.of(Map.of("id", "forbidden-fixture-tool", "type", "function",
                    "function", Map.of("name", "forbidden", "arguments", "{}"))));
            Map<String, Object> response = Map.of("id", "synthetic-" + reservation.ordinal(),
                    "object", "chat.completion", "model", "loopback-protocol-fixture",
                    "choices", List.of(Map.of("index", 0, "message", message,
                            "finish_reason", tools ? "tool_calls" : length ? "length" : "stop")),
                    "usage", Map.of("prompt_tokens", 100, "completion_tokens", 8, "total_tokens", 108));
            Map<String, Object> responseMetadata = new LinkedHashMap<>();
            responseMetadata.put("finish_reason", tools ? "TOOL_EXECUTION" : length ? "LENGTH" : "STOP");
            responseMetadata.put("response_text", content);
            responseMetadata.put("tool_call_count", tools ? 1 : 0);
            responseMetadata.put("input_tokens", 100);
            responseMetadata.put("output_tokens", 8);
            responseMetadata.put("response_model", "loopback-protocol-fixture");
            send(exchange, state, 200, JSON.writeValueAsBytes(response), responseMetadata);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            state.put("response_interrupted", true);
        } catch (IOException writeFailure) {
            state.put("transport_write_failed", true);
        } finally {
            try {
                if (reserved) ledger.release();
            } finally {
                state.put("request_finished_at", Instant.now().toString());
                state.completed.countDown();
                exchange.close();
            }
        }
    }

    private List<String> contract(HttpExchange exchange, String body, String userText) {
        List<String> errors = new ArrayList<>();
        if (!"POST".equals(exchange.getRequestMethod())
                || !"/v1/chat/completions".equals(exchange.getRequestURI().getPath())
                || exchange.getRequestURI().getQuery() != null) errors.add("method/path");
        JsonNode request;
        try { request = JSON.readTree(body); }
        catch (RuntimeException malformed) { errors.add("request JSON invalid"); return errors; }
        if (request == null || !request.isObject()) { errors.add("request must be object"); return errors; }
        Set<String> allowed = Set.of("model", "temperature", "max_tokens", "messages", "response_format", "stream");
        if (!allowed.containsAll(request.propertyNames())) errors.add("unexpected request fields");
        if (!request.path("model").isString() || !model.equals(request.path("model").asString())) errors.add("model");
        if (!request.path("temperature").isNumber() || request.path("temperature").asDouble() != 0.0) errors.add("temperature");
        if (!request.path("max_tokens").isIntegralNumber() || request.path("max_tokens").asInt() != 128) errors.add("max_tokens");
        if (request.has("stream") && (!request.path("stream").isBoolean() || request.path("stream").asBoolean())) errors.add("stream");
        JsonNode messages = request.path("messages");
        if (!messages.isArray() || messages.size() != 2) errors.add("exactly two messages");
        else {
            checkMessage(messages.get(0), "system", expectedSystem, errors);
            checkMessage(messages.get(1), "user", userText, errors);
        }
        if (request.has("tools") || request.has("tool_choice")) errors.add("tools forbidden");
        JsonNode format = request.path("response_format");
        if (!"json_schema".equals(format.path("type").asString())) errors.add("response_format.type");
        JsonNode schema = format.path("json_schema");
        if (!"ObservationIntent".equals(schema.path("name").asString())) errors.add("schema.name");
        if (!schema.path("strict").isBoolean() || !schema.path("strict").asBoolean()) errors.add("schema.strict");
        if (!expectedSchema.equals(schema.path("schema"))) errors.add("schema differs from frozen config");
        return errors;
    }

    private static void checkMessage(JsonNode message, String role, String content, List<String> errors) {
        if (!message.isObject() || !message.propertyNames().equals(Set.of("role", "content"))
                || !message.path("role").isString() || !role.equals(message.path("role").asString())
                || !message.path("content").isString() || !content.equals(message.path("content").asString()))
            errors.add(role + " message differs");
    }

    private void reject(RequestState state, String reason, int bytes) {
        state.put("rejection_reason", reason);
        state.put("rejected_body_bytes", bytes);
        synchronized (this) { rejections.merge(reason, 1, Integer::sum); }
    }

    private static byte[] errorBody(String reason) {
        return JSON.writeValueAsBytes(Map.of("error", Map.of("message", "Synthetic loopback failure", "code", reason)));
    }

    private static void send(HttpExchange exchange, RequestState state, int status, byte[] body,
                             Map<String, Object> responseMetadata) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
        // Headers or a planned response are not evidence that the body was sent successfully.
        state.put("http_status", status);
        state.put("response_body", new String(body, StandardCharsets.UTF_8));
        state.put("raw_response_body", new String(body, StandardCharsets.UTF_8));
        if (responseMetadata != null) state.putAll(responseMetadata);
    }

    @Override public void close() {
        closed = true;
        server.stop(0);
        executor.shutdownNow();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static final class RequestState {
        private final String id;
        private final String text;
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private final CountDownLatch completed = new CountDownLatch(1);
        RequestState(String id, String text) {
            this.id = id;
            this.text = text;
            metadata.put("id", id);
            metadata.put("loopback_requests", 0);
            metadata.put("supplier_attempts", 0);
            metadata.put("request_bytes", 0);
            metadata.put("received_body_bytes", 0);
            metadata.put("rejected_body_bytes", 0);
            metadata.put("request_contract_errors", List.of());
            for (String name : List.of("http_status", "finish_reason", "response_text", "tool_call_count",
                    "input_tokens", "output_tokens", "response_model", "request_body", "rejection_reason",
                    "request_started_at", "request_finished_at", "raw_response_body")) metadata.put(name, null);
        }
        synchronized int incrementRequests() {
            int count = ((Number) metadata.get("loopback_requests")).intValue() + 1;
            metadata.put("loopback_requests", count);
            metadata.put("request_started_at", Instant.now().toString());
            return count;
        }
        synchronized boolean hasRequests() { return ((Number) metadata.get("loopback_requests")).intValue() > 0; }
        synchronized void put(String key, Object value) { metadata.put(key, value); }
        synchronized void putAll(Map<String, Object> values) { metadata.putAll(values); }
        synchronized Map<String, Object> copy() { return new LinkedHashMap<>(metadata); }
    }

    private record Reservation(boolean accepted, String reason, int ordinal) {}

    /** All accepted calls reserve once; release frees concurrency only, never calls or bytes. */
    private static final class Ledger {
        private final int maxCalls;
        private final int maxRequestBytes;
        private long maxTotalBytes;
        private int calls;
        private long bytes;
        private int active;
        private int maxActive;
        Ledger(int maxCalls, int maxRequestBytes, long maxTotalBytes) {
            this.maxCalls = maxCalls;
            this.maxRequestBytes = maxRequestBytes;
            this.maxTotalBytes = maxTotalBytes;
        }
        synchronized Reservation reserve(long size) {
            String rejection = active >= 1 ? "CONCURRENCY_LIMIT"
                    : calls >= maxCalls ? "CALL_LIMIT"
                    : size < 0 || size > maxRequestBytes ? "REQUEST_BYTES_LIMIT"
                    : size > maxTotalBytes - bytes ? "TOTAL_BYTES_LIMIT" : null;
            if (rejection != null) return new Reservation(false, rejection, calls);
            calls++;
            bytes += size;
            active++;
            maxActive = Math.max(maxActive, active);
            return new Reservation(true, null, calls);
        }
        synchronized void release() {
            if (active != 1) throw new IllegalStateException("Unbalanced fixture slot release");
            active--;
        }
        synchronized void shrinkTotalTo(long limit) { maxTotalBytes = Math.min(maxTotalBytes, limit); }
        synchronized Map<String, Object> snapshot() {
            return Map.of("calls", calls, "bytes", bytes, "active", active,
                    "max_active", maxActive, "effective_max_total_bytes", maxTotalBytes);
        }
    }

    static Map<String, Object> budgetSelfTest() {
        List<String> names = new ArrayList<>();
        Ledger single = new Ledger(2, 1, 2);
        check(!single.reserve(2).accepted() && single.reserve(1).accepted(), "single byte boundary");
        single.release();
        check(((Number) single.snapshot().get("bytes")).longValue() == 1, "reject does not reserve bytes");
        names.add("single_request_byte_boundary");

        Ledger total = new Ledger(3, 2, 3);
        check(total.reserve(2).accepted(), "total initial reserve"); total.release();
        check(!total.reserve(2).accepted() && total.reserve(1).accepted(), "total exact boundary"); total.release();
        check(((Number) total.snapshot().get("bytes")).longValue() == 3, "total exact sum");
        names.add("total_byte_boundary");

        Ledger calls = new Ledger(1, 2, 4);
        check(calls.reserve(1).accepted(), "call reserve"); calls.release();
        check(!calls.reserve(1).accepted() && ((Number) calls.snapshot().get("calls")).intValue() == 1, "call cap");
        names.add("call_boundary");

        Ledger concurrent = new Ledger(3, 2, 6);
        check(concurrent.reserve(1).accepted(), "first concurrency slot");
        AtomicReference<Reservation> result = new AtomicReference<>();
        Thread contender = new Thread(() -> result.set(concurrent.reserve(1)), "fixture-ledger-self-test");
        contender.setDaemon(true);
        contender.start();
        try { contender.join(2000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        check(!contender.isAlive() && result.get() != null && !result.get().accepted()
                && "CONCURRENCY_LIMIT".equals(result.get().reason()), "concurrent rejection");
        concurrent.release();
        check(((Number) concurrent.snapshot().get("max_active")).intValue() == 1, "max concurrency");
        names.add("concurrency_one");

        Ledger noRefund = new Ledger(2, 3, 4);
        check(noRefund.reserve(2).accepted(), "first nonrefundable reserve"); noRefund.release();
        check(noRefund.reserve(2).accepted(), "second nonrefundable reserve"); noRefund.release();
        check(!noRefund.reserve(1).accepted(), "rejected third call");
        check(((Number) noRefund.snapshot().get("calls")).intValue() == 2
                && ((Number) noRefund.snapshot().get("bytes")).longValue() == 4
                && ((Number) noRefund.snapshot().get("active")).intValue() == 0, "reservations never refunded");
        names.add("reservation_no_refund_and_rejection_no_increment");
        return Map.of("pass", names.size(), "fail", 0, "checks", List.copyOf(names));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Fixture budget self-test: " + message);
    }
}

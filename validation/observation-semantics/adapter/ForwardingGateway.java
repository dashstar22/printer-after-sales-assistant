package local.printer.assistant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded single-attempt bridge. Only real() can select the fixed HTTPS supplier. */
final class ForwardingGateway implements EvaluationEndpoint {
    private static final String FIXED_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final URI FIXED_ENDPOINT = URI.create(FIXED_BASE + "/chat/completions");
    private static final int MAX_RESPONSE_BYTES = 65536;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final JsonNode schema;
    private final String system;
    private final String model;
    private final String key;
    private final URI upstream;
    private final boolean real;
    private final int maxCalls;
    private final int maxRequestBytes;
    private final long maxBytes;
    private final int requestMillis;
    private final long runDeadline;
    private final HttpServer server;
    private final ExecutorService workers;
    private final List<String> contractErrors = new ArrayList<>();
    private final Map<String, Integer> rejections = new LinkedHashMap<>();
    private final Set<String> seen = new java.util.HashSet<>();
    private volatile State expected;
    private volatile boolean closed;
    private volatile Socket activeSocket;
    private String halted;
    private int calls;
    private int loopbackRequests;
    private int active;
    private int maxActive;
    private long bytes;

    static ForwardingGateway real(JsonNode config, String system, String apiKey) throws IOException {
        require(FIXED_BASE.equals(config.path("model").path("base_url").asString()), "FIXED_HTTPS_ENDPOINT_REQUIRED");
        return new ForwardingGateway(config, system, FIXED_ENDPOINT, apiKey, true,
                config.path("budget").path("max_attempts").asInt(),
                config.path("budget").path("max_request_bytes").asInt(),
                config.path("budget").path("max_total_request_bytes").asLong());
    }

    /** Only package-local test code can supply a loopback URI; it is never a CLI override. */
    static ForwardingGateway test(JsonNode config, String system, URI upstream, String syntheticKey,
                                  int maxCalls, int maxRequestBytes, long maxTotalBytes) throws IOException {
        require("http".equals(upstream.getScheme()) && "127.0.0.1".equals(upstream.getHost())
                && upstream.getPort() > 0 && upstream.getUserInfo() == null && upstream.getQuery() == null
                && upstream.getFragment() == null && "/v1/chat/completions".equals(upstream.getPath()),
                "TEST_UPSTREAM_MUST_BE_LOOPBACK");
        return new ForwardingGateway(config, system, upstream, syntheticKey, false,
                maxCalls, maxRequestBytes, maxTotalBytes);
    }

    private ForwardingGateway(JsonNode config, String system, URI upstream, String key, boolean real,
                              int maxCalls, int maxRequestBytes, long maxTotalBytes) throws IOException {
        this.system = Objects.requireNonNull(system);
        this.schema = config.path("model").path("schema");
        this.model = config.path("model").path("requested_model").asString();
        require("qwen-plus".equals(model), "FROZEN_MODEL_REQUIRED");
        require(key != null && key.length() >= 8 && key.length() <= 4096
                && key.chars().allMatch(c -> c >= 33 && c <= 126), "CREDENTIAL_INVALID");
        this.key = key;
        require(maxCalls >= 0 && maxCalls <= 36 && maxRequestBytes >= 0 && maxRequestBytes <= 4096
                && maxTotalBytes >= 0 && maxTotalBytes <= 147456, "BUDGET_CAN_ONLY_SHRINK");
        this.maxCalls = maxCalls;
        this.maxRequestBytes = maxRequestBytes;
        this.maxBytes = maxTotalBytes;
        this.requestMillis = Math.min(5000, config.path("budget").path("per_case_timeout_ms").asInt());
        require(requestMillis >= 10, "TIMEOUT_INVALID");
        this.upstream = upstream;
        this.real = real;
        this.runDeadline = System.nanoTime() + Math.min(300, config.path("budget").path("max_run_wall_seconds").asLong()) * 1_000_000_000L;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(work -> {
            var thread = new Thread(work, "observation-evaluation-gateway");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    @Override public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    @Override public void expect(String id, String text) {
        require(!closed, "GATEWAY_CLOSED");
        expected = new State(Objects.requireNonNull(id), Objects.requireNonNull(text),
                Math.min(runDeadline, System.nanoTime() + requestMillis * 1_000_000L));
    }

    @Override public Map<String, Object> current() {
        State state = expected;
        if (state == null) return Map.of();
        if (state.hasRequest()) {
            try { state.done.await(200, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        return state.copy();
    }

    @Override public synchronized Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("calls", calls);
        result.put("bytes", bytes);
        result.put("active", active);
        result.put("max_active", maxActive);
        result.put("effective_max_total_bytes", maxBytes);
        result.put("loopback_requests", loopbackRequests);
        result.put("contract_errors", List.copyOf(contractErrors));
        result.put("rejections", new LinkedHashMap<>(rejections));
        result.put("configured_max_calls", maxCalls);
        result.put("configured_max_request_bytes", maxRequestBytes);
        result.put("configured_max_total_bytes", maxBytes);
        result.put("upstream_requests", calls);
        result.put("real_upstream_requests", real ? calls : 0);
        result.put("forwarding_implemented", true);
        result.put("mode", real ? "real_model" : "loopback_forwarding_test");
        result.put("halt_reason", halted);
        return result;
    }

    private void handle(HttpExchange exchange) throws IOException {
        State state = expected;
        if (state == null) state = new State("UNEXPECTED", "", System.nanoTime());
        synchronized (this) { loopbackRequests++; }
        int caseRequests = state.arrive();
        boolean reserved = false;
        try {
            byte[] body = exchange.getRequestBody().readNBytes(4097);
            String text = new String(body, StandardCharsets.UTF_8);
            state.put("received_body_bytes", body.length);
            state.put("received_body_bytes_is_lower_bound", body.length == 4097);
            if (containsCredential(body)) {
                reject(state, "CREDENTIAL_IN_REQUEST", body.length);
                reply(exchange, state, 400, error("CREDENTIAL_IN_REQUEST"));
                return;
            }
            state.put("request_body", text);
            if (body.length > 4096) {
                reject(state, "REQUEST_BYTES_LIMIT", body.length);
                reply(exchange, state, 429, error("REQUEST_BYTES_LIMIT"));
                return;
            }
            List<String> errors = contract(exchange, text, state.text);
            state.put("request_contract_errors", errors);
            if (!errors.isEmpty()) {
                synchronized (this) { for (String item : errors) contractErrors.add(state.id + ":" + item); }
                reject(state, "REQUEST_CONTRACT_INVALID", body.length);
                reply(exchange, state, 400, error("REQUEST_CONTRACT_INVALID"));
                return;
            }
            String rejected = reserve(state, caseRequests, body.length);
            if (rejected != null) {
                reject(state, rejected, body.length);
                reply(exchange, state, 429, error(rejected));
                return;
            }
            reserved = true;
            state.put("supplier_attempts", 1);
            state.put("request_bytes", body.length);
            state.put("forwarded_body_sha256", hash(body));
            Response response;
            try {
                response = oneAttempt(body, state.deadline);
            } catch (IOException transport) {
                halt("UPSTREAM_TRANSPORT_FAILURE");
                state.put("upstream_failure", "UPSTREAM_TRANSPORT_FAILURE");
                reply(exchange, state, 502, error("UPSTREAM_TRANSPORT_FAILURE"));
                return;
            }
            state.put("upstream_http_status", response.status());
            // Supplier errors can echo Authorization. Never persist or return such a body.
            String responseText = new String(response.body(), StandardCharsets.UTF_8);
            if (containsCredential(response.body())) {
                halt("CREDENTIAL_ECHO_REJECTED");
                state.put("upstream_failure", "CREDENTIAL_ECHO_REJECTED");
                reply(exchange, state, 502, error("CREDENTIAL_ECHO_REJECTED"));
                return;
            }
            state.put("upstream_response_sha256", hash(response.body()));
            state.put("raw_response_body", responseText);
            if (response.status() < 200 || response.status() >= 300) halt("UPSTREAM_HTTP_FAILURE");
            Map<String, Object> meta = metadata(response.body());
            // No application retry or redirect follows this forwarding attempt.
            reply(exchange, state, response.status(), response.body());
            state.putAll(meta);
        } catch (IOException transport) {
            halt("LOCAL_TRANSPORT_FAILURE");
            state.put("transport_write_failed", true);
        } catch (RuntimeException invalid) {
            halt("GATEWAY_INTERNAL_FAILURE");
            state.put("upstream_failure", "GATEWAY_INTERNAL_FAILURE");
            try { reply(exchange, state, 502, error("GATEWAY_INTERNAL_FAILURE")); }
            catch (IOException ignored) { state.put("transport_write_failed", true); }
        } finally {
            if (reserved) synchronized (this) { active--; }
            state.put("request_finished_at", Instant.now().toString());
            state.done.countDown();
            exchange.close();
        }
    }

    private synchronized String reserve(State state, int requests, int size) {
        String reject = closed ? "GATEWAY_CLOSED" : halted != null ? "GATEWAY_HALTED"
                : System.nanoTime() >= state.deadline ? "REQUEST_DEADLINE"
                : active >= 1 ? "CONCURRENCY_LIMIT" : requests > 1 || seen.contains(state.id) ? "DUPLICATE_CASE_REQUEST"
                : calls >= maxCalls ? "CALL_LIMIT" : size > maxRequestBytes ? "REQUEST_BYTES_LIMIT"
                : size > maxBytes - bytes ? "TOTAL_BYTES_LIMIT" : null;
        if (reject != null) return reject;
        seen.add(state.id);
        calls++;
        bytes += size;
        active++;
        maxActive = Math.max(maxActive, active);
        return null;
    }

    private synchronized void halt(String reason) { if (halted == null) halted = reason; }
    private void reject(State state, String reason, int size) {
        halt(reason);
        state.put("rejection_reason", reason);
        state.put("rejected_body_bytes", size);
        synchronized (this) { rejections.merge(reason, 1, Integer::sum); }
    }

    private List<String> contract(HttpExchange exchange, String body, String user) {
        var errors = new ArrayList<String>();
        if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().toString().equals("/v1/chat/completions")) errors.add("method/path");
        JsonNode request;
        try { request = JSON.readTree(body); }
        catch (RuntimeException malformed) { return List.of("request JSON invalid"); }
        if (request == null || !request.isObject()) return List.of("request object required");
        if (!Set.of("model", "temperature", "max_tokens", "messages", "response_format", "stream").containsAll(request.propertyNames())) errors.add("unexpected fields");
        if (!model.equals(request.path("model").asString())) errors.add("model");
        if (!request.path("temperature").isNumber() || request.path("temperature").asDouble() != 0) errors.add("temperature");
        if (!request.path("max_tokens").isIntegralNumber() || request.path("max_tokens").asInt() != 128) errors.add("max_tokens");
        if (request.has("stream") && (!request.path("stream").isBoolean() || request.path("stream").asBoolean())) errors.add("stream");
        JsonNode messages = request.path("messages");
        if (!messages.isArray() || messages.size() != 2) errors.add("two messages required");
        else {
            message(messages.get(0), "system", system, errors);
            message(messages.get(1), "user", user, errors);
        }
        var format = request.path("response_format");
        var detail = format.path("json_schema");
        if (!"json_schema".equals(format.path("type").asString()) || !"ObservationIntent".equals(detail.path("name").asString())
                || !detail.path("strict").isBoolean() || !detail.path("strict").asBoolean()
                || !schema.equals(detail.path("schema"))) errors.add("strict schema");
        return errors;
    }

    private static void message(JsonNode value, String role, String text, List<String> errors) {
        if (!value.isObject() || !value.propertyNames().equals(Set.of("role", "content"))
                || !role.equals(value.path("role").asString()) || !value.path("content").isString()
                || !text.equals(value.path("content").asString())) errors.add(role + " differs");
    }

    /** One TCP connection, one HTTP request. No HTTP library can silently replay it. */
    private Response oneAttempt(byte[] body, long deadline) throws IOException {
        Socket plain = new Socket(Proxy.NO_PROXY);
        register(plain, deadline);
        Socket registered = plain;
        int port = upstream.getPort() > 0 ? upstream.getPort() : 443;
        try {
            plain.connect(new InetSocketAddress(upstream.getHost(), port), remaining(deadline));
            Socket socket = plain;
            if (upstream.getScheme().equals("https")) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, upstream.getHost(), port, true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.setSoTimeout(remaining(deadline));
                register(tls, deadline);
                registered = tls;
                tls.startHandshake();
                socket = tls;
            }
            try (Socket connection = socket) {
                connection.setSoTimeout(remaining(deadline));
                String host = upstream.getHost() + (upstream.getPort() > 0 ? ":" + port : "");
                String head = "POST " + upstream.getRawPath() + " HTTP/1.1\r\nHost: " + host
                        + "\r\nContent-Type: application/json\r\nAccept: application/json\r\nAuthorization: Bearer " + key
                        + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                connection.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                connection.getOutputStream().write(body);
                connection.getOutputStream().flush();
                return readResponse(connection, deadline);
            }
        } finally {
            synchronized (this) { if (activeSocket == registered) activeSocket = null; }
            plain.close();
        }
    }

    private synchronized void register(Socket socket, long deadline) throws IOException {
        if (closed || halted != null || System.nanoTime() >= deadline) {
            socket.close();
            throw new IOException("GATEWAY_STOPPED_BEFORE_CONNECT");
        }
        // Registration and halt's capture share one lock. Closing an unconnected socket also
        // makes a subsequent connect fail, so stop cannot race into an untracked connection.
        activeSocket = socket;
    }

    private static Response readResponse(Socket socket, long deadline) throws IOException {
        InputStream input = socket.getInputStream();
        String statusLine = line(socket, input, deadline, 4096);
        if (!statusLine.matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: .*)?")) throw new IOException("UPSTREAM_STATUS_INVALID");
        int status = Integer.parseInt(statusLine.substring(9, 12));
        var headers = new LinkedHashMap<String, String>();
        int total = statusLine.length();
        for (;;) {
            String header = line(socket, input, deadline, 8192);
            total += header.length() + 2;
            if (total > 16384) throw new IOException("UPSTREAM_HEADERS_TOO_LARGE");
            if (header.isEmpty()) break;
            int separator = header.indexOf(':');
            if (separator <= 0 || Character.isWhitespace(header.charAt(0))) throw new IOException("UPSTREAM_HEADER_INVALID");
            String name = header.substring(0, separator).toLowerCase(Locale.ROOT);
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) throw new IOException("UPSTREAM_HEADER_INVALID");
            if (headers.putIfAbsent(name, header.substring(separator + 1).strip()) != null
                    && Set.of("content-length", "transfer-encoding").contains(name)) throw new IOException("UPSTREAM_LENGTH_AMBIGUOUS");
        }
        if (status < 200) throw new IOException("UPSTREAM_INFORMATIONAL_UNSUPPORTED");
        String encoding = headers.get("content-encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("UPSTREAM_ENCODING_UNSUPPORTED");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String transfer = headers.get("transfer-encoding");
        String length = headers.get("content-length");
        if (transfer != null) {
            if (!transfer.equalsIgnoreCase("chunked") || length != null) throw new IOException("UPSTREAM_LENGTH_AMBIGUOUS");
            for (;;) {
                String chunkLine = line(socket, input, deadline, 1024).split(";", 2)[0];
                long size;
                try { size = Long.parseLong(chunkLine, 16); }
                catch (NumberFormatException invalid) { throw new IOException("UPSTREAM_CHUNK_INVALID"); }
                if (size < 0 || size > MAX_RESPONSE_BYTES - body.size()) throw new IOException("UPSTREAM_RESPONSE_TOO_LARGE");
                if (size == 0) {
                    int trailerBytes = 0;
                    for (;;) {
                        String trailer = line(socket, input, deadline, 4096);
                        trailerBytes += trailer.length() + 2;
                        if (trailerBytes > 8192) throw new IOException("UPSTREAM_TRAILERS_TOO_LARGE");
                        if (trailer.isEmpty()) break;
                    }
                    break;
                }
                readBytes(socket, input, deadline, body, (int) size);
                if (!line(socket, input, deadline, 2).isEmpty()) throw new IOException("UPSTREAM_CHUNK_END_INVALID");
            }
        } else if (length != null) {
            int size;
            try { size = Integer.parseInt(length); }
            catch (NumberFormatException invalid) { throw new IOException("UPSTREAM_LENGTH_INVALID"); }
            if (size < 0 || size > MAX_RESPONSE_BYTES) throw new IOException("UPSTREAM_RESPONSE_TOO_LARGE");
            readBytes(socket, input, deadline, body, size);
        } else {
            byte[] buffer = new byte[4096];
            for (;;) {
                socket.setSoTimeout(remaining(deadline));
                int n = input.read(buffer);
                if (n < 0) break;
                if (n > MAX_RESPONSE_BYTES - body.size()) throw new IOException("UPSTREAM_RESPONSE_TOO_LARGE");
                body.write(buffer, 0, n);
            }
        }
        return new Response(status, body.toByteArray());
    }

    private static String line(Socket socket, InputStream input, long deadline, int maximum) throws IOException {
        var out = new ByteArrayOutputStream();
        int last = -1;
        for (;;) {
            socket.setSoTimeout(remaining(deadline));
            int next = input.read();
            if (next < 0) throw new EOFException("UPSTREAM_CLOSED");
            if (last == '\r' && next == '\n') {
                byte[] bytes = out.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            if (next == '\n' || out.size() > maximum) throw new IOException("UPSTREAM_LINE_INVALID");
            out.write(next);
            last = next;
        }
    }

    private static void readBytes(Socket socket, InputStream input, long deadline,
                                  ByteArrayOutputStream out, int size) throws IOException {
        byte[] buffer = new byte[Math.min(4096, Math.max(1, size))];
        for (int remaining = size; remaining > 0;) {
            socket.setSoTimeout(remaining(deadline));
            int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) throw new EOFException("UPSTREAM_TRUNCATED");
            out.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static int remaining(long deadline) throws IOException {
        long left = (deadline - System.nanoTime()) / 1_000_000;
        if (left <= 0) throw new java.net.SocketTimeoutException("UPSTREAM_DEADLINE");
        return (int) Math.min(5000, Math.max(1, left));
    }

    private static Map<String, Object> metadata(byte[] body) {
        var meta = new LinkedHashMap<String, Object>();
        try {
            JsonNode response = JSON.readTree(body);
            var choices = response.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return meta;
            var choice = choices.get(0);
            var message = choice.path("message");
            String finish = choice.path("finish_reason").asString();
            meta.put("finish_reason", switch (finish) { case "stop" -> "STOP"; case "length" -> "LENGTH";
                case "tool_calls", "function_call" -> "TOOL_EXECUTION"; default -> finish; });
            meta.put("response_text", message.path("content").isString() ? message.path("content").asString() : null);
            meta.put("tool_call_count", message.path("tool_calls").isArray() ? message.path("tool_calls").size() : 0);
            meta.put("response_model", response.path("model").isString() ? response.path("model").asString() : null);
            meta.put("input_tokens", usage(response.path("usage").path("prompt_tokens")));
            meta.put("output_tokens", usage(response.path("usage").path("completion_tokens")));
        } catch (RuntimeException malformed) { /* Production interpreter decides protocol validity. */ }
        return meta;
    }

    private static Long usage(JsonNode node) { return node.isIntegralNumber() && node.canConvertToLong() && node.asLong() >= 0 ? node.asLong() : null; }
    private boolean containsCredential(byte[] body) {
        if (new String(body, StandardCharsets.UTF_8).contains(key)) return true;
        try { return containsCredential(JSON.readTree(body)); }
        catch (RuntimeException malformed) { return false; }
    }
    private boolean containsCredential(JsonNode node) {
        if (node == null) return false;
        if (node.isString()) return node.asString().contains(key);
        if (node.isObject()) {
            for (String name : node.propertyNames())
                if (name.contains(key) || containsCredential(node.path(name))) return true;
        } else if (node.isArray()) {
            for (var value : node) if (containsCredential(value)) return true;
        }
        return false;
    }
    private static byte[] error(String reason) { return JSON.writeValueAsBytes(Map.of("error", Map.of("message", "Evaluation gateway stopped", "code", reason))); }
    private static void reply(HttpExchange exchange, State state, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
        state.put("http_status", status);
    }
    private static String hash(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    private static void require(boolean good, String code) { if (!good) throw new IllegalArgumentException(code); }
    private record Response(int status, byte[] body) {}

    @Override public void halt() {
        Socket socket;
        synchronized (this) {
            if (halted == null) halted = "CALLER_STOPPED";
            socket = activeSocket;
        }
        if (socket != null) try { socket.close(); } catch (IOException ignored) { /* no sensitive diagnostics */ }
    }
    @Override public void close() {
        closed = true;
        halt();
        server.stop(0);
        workers.shutdownNow();
        try { workers.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static final class State {
        final String id;
        final String text;
        final long deadline;
        final CountDownLatch done = new CountDownLatch(1);
        private final Map<String, Object> meta = new LinkedHashMap<>();
        State(String id, String text, long deadline) {
            this.id = id; this.text = text; this.deadline = deadline;
            meta.put("id", id);
            for (String name : List.of("loopback_requests", "supplier_attempts", "request_bytes", "received_body_bytes", "rejected_body_bytes")) meta.put(name, 0);
            meta.put("request_contract_errors", List.of());
            for (String name : List.of("http_status", "finish_reason", "response_text", "tool_call_count", "input_tokens", "output_tokens",
                    "response_model", "request_body", "rejection_reason", "request_started_at", "request_finished_at", "raw_response_body")) meta.put(name, null);
        }
        synchronized int arrive() {
            int count = ((Number) meta.get("loopback_requests")).intValue() + 1;
            meta.put("loopback_requests", count);
            meta.put("request_started_at", Instant.now().toString());
            return count;
        }
        synchronized boolean hasRequest() { return ((Number) meta.get("loopback_requests")).intValue() > 0; }
        synchronized void put(String name, Object value) { meta.put(name, value); }
        synchronized void putAll(Map<String, Object> values) { meta.putAll(values); }
        synchronized Map<String, Object> copy() { return new LinkedHashMap<>(meta); }
    }

    /** Invalid policies are rejected before a listener or upstream socket is created. */
    static Map<String, Object> policySelfTest(JsonNode config, String system) {
        List<String> checks = new ArrayList<>();
        String baseline = JSON.writeValueAsString(config);
        String configuredBase = config.path("model").path("base_url").asString();
        for (String bad : List.of("http://dashscope.aliyuncs.com/compatible-mode/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1?x=1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/",
                "https://example.invalid/compatible-mode/v1")) {
            JsonNode altered = JSON.readTree(baseline.replace(configuredBase, bad));
            rejected(() -> real(altered, system, "synthetic-policy-key"));
            checks.add("reject_real_endpoint_" + checks.size());
        }
        for (String bad : List.of("http://example.invalid/v1/chat/completions",
                "http://user@127.0.0.1:1/v1/chat/completions",
                "http://127.0.0.1:1/v1/chat/completions?query=1",
                "http://127.0.0.1:1/v1/chat/completions#fragment",
                "http://127.0.0.1:1/another/path",
                "https://127.0.0.1:1/v1/chat/completions")) {
            rejected(() -> test(config, system, URI.create(bad), "synthetic-policy-key", 36, 4096, 147456));
            checks.add("reject_test_endpoint_" + checks.size());
        }
        URI local = URI.create("http://127.0.0.1:1/v1/chat/completions");
        for (String bad : List.of("", "short", "contains\r\nheader", " key-with-space", "nonascii-密钥")) {
            rejected(() -> test(config, system, local, bad, 36, 4096, 147456));
            checks.add("reject_credential_shape_" + checks.size());
        }
        for (long[] limit : List.of(new long[]{37, 4096, 147456}, new long[]{36, 4097, 147456},
                new long[]{36, 4096, 147457})) {
            rejected(() -> test(config, system, local, "synthetic-policy-key", (int) limit[0], (int) limit[1], limit[2]));
            checks.add("reject_relaxed_budget_" + checks.size());
        }
        return Map.of("pass", checks.size(), "fail", 0, "checks", List.copyOf(checks), "upstream_requests", 0);
    }

    @FunctionalInterface private interface PolicyAction { ForwardingGateway run() throws IOException; }
    private static void rejected(PolicyAction action) {
        try (ForwardingGateway unintended = action.run()) {
            throw new AssertionError("INVALID_POLICY_ACCEPTED");
        } catch (IllegalArgumentException expected) {
            // Values used by this check are synthetic; diagnostics never include any value.
        } catch (IOException unexpected) {
            throw new AssertionError("INVALID_POLICY_REACHED_IO");
        }
    }
}

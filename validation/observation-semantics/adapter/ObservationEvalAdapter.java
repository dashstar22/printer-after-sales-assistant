package local.printer.assistant;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import local.printer.config.DependencyProperties;
import local.printer.operations.ExternalCallBudget;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Standalone test adapter. Its only HTTP destination is its own loopback fixture. */
public final class ObservationEvalAdapter {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static final Set<String> SCENARIOS = Set.of("success", "invalid-json", "second-sentinel-invalid",
            "third-sentinel-invalid", "tool-response", "finish-length", "timeout", "http-401",
            "http-429", "http-503", "call-budget", "byte-budget", "total-byte-budget");
    private static final List<String> RECORD_FIELDS = List.of("id", "status", "attempts", "request_bytes",
            "input_tokens", "output_tokens", "elapsed_ms", "response_model", "finish_reason",
            "response_text", "tool_call_count", "effective_kind", "reason", "skip_reason");

    private ObservationEvalAdapter() {}

    public static void main(String[] args) {
        try {
            require(args.length == 3, "ARGUMENT_COUNT");
            run(Path.of(args[0]), Path.of(args[1]), args[2]);
        } catch (Exception | AssertionError failure) {
            // No exception body, HTTP headers, or environment dump in console diagnostics.
            System.err.println("ADAPTER_FAILED: " + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    static void run(Path assetRoot, Path output, String scenario) throws Exception {
        require(SCENARIOS.contains(scenario), "FIXTURE_SCENARIO_REQUIRED");
        runWithEndpoint(assetRoot, output, scenario, "fixture", null);
    }

    @FunctionalInterface
    interface EndpointFactory {
        EvaluationEndpoint create(JsonNode config, String system, int calls, int requestBytes, long totalBytes) throws Exception;
    }

    static void runWithEndpoint(Path assetRoot, Path output, String scenario, String mode, EndpointFactory factory) throws Exception {
        boolean real = mode.equals("real");
        require(Set.of("fixture", "forward-fixture", "real").contains(mode), "UNKNOWN_MODE");
        String executionKind = real ? "real_model" : "synthetic_test";
        assetRoot = assetRoot.toRealPath();
        Path repo = assetRoot.getParent().getParent().toRealPath();
        require(assetRoot.equals(repo.resolve("validation/observation-semantics"))
                || assetRoot.equals(repo.resolve("validation/observation-semantics-v2")), "FIXED_ASSET_ROOT_REQUIRED");
        output = safeOutput(repo, output);
        verifyFrozen(assetRoot, repo);
        JsonNode config = JSON.readTree(Files.readString(assetRoot.resolve("config.json")));
        String model = config.path("model").path("requested_model").asString();
        int calls = config.path("budget").path("max_attempts").asInt();
        int requestBytes = config.path("budget").path("max_request_bytes").asInt();
        long totalBytes = config.path("budget").path("max_total_request_bytes").asLong();
        require(calls == 36 && requestBytes == 4096 && totalBytes == 147456, "FROZEN_BUDGET_REQUIRED");
        if (scenario.equals("call-budget")) calls = 2;
        if (scenario.equals("byte-budget")) requestBytes = 1;
        Field systemField = ObservationIntentInterpreter.class.getDeclaredField("SYSTEM");
        systemField.setAccessible(true);
        String system = (String) systemField.get(null);
        var samples = orderedSamples(assetRoot, config);
        var records = new ArrayList<Map<String, Object>>();
        var evidence = new ArrayList<Map<String, Object>>();
        var stoppedOn = new ArrayList<String>();
        for (var reason : config.path("execution_order").path("stop_on")) stoppedOn.add(reason.asString());
        var env = isolatedEnvironment(scenario.equals("timeout") ? 1000 : 5000);
        var execution = new AssistantExecution(env);
        var external = new InMemoryBudget(env, calls);
        Field externalField = AssistantExecution.class.getDeclaredField("externalBudget");
        externalField.setAccessible(true);
        externalField.set(execution, external);
        var run = new LinkedHashMap<String, Object>();
        String started = Instant.now().toString();
        long deadline = System.nanoTime() + config.path("budget").path("max_run_wall_seconds").asLong() * 1_000_000_000L;
        String stoppedReason = null;
        String stoppedId = null;
        Map<String, Object> fixtureSummary;
        URI loopback;
        EvaluationEndpoint fixture = factory == null
                ? new LoopbackFixture(scenario, system, config.path("model").path("schema"), model, calls, requestBytes, totalBytes)
                : factory.create(config, system, calls, requestBytes, totalBytes);
        try (fixture) {
            loopback = fixture.baseUrl();
            require(loopback.getScheme().equals("http") && loopback.getHost().equals("127.0.0.1"), "LOOPBACK_ONLY");
            // A non-secret, fixed placeholder. No real credential or upstream URL is consumed.
            var ai = new DependencyProperties.Ai(true, loopback, model, "loopback-fixture-placeholder");
            for (var sample : samples) {
                String id = sample.path("id").asString();
                if (stoppedReason != null || System.nanoTime() >= deadline) {
                    if (stoppedReason == null) stoppedReason = "RUN_START_DEADLINE";
                    records.add(skipped(id, "STOPPED_AFTER_" + stoppedReason));
                    continue;
                }
                String text = sample.path("text").asString();
                fixture.expect(id, text);
                external.expect(id);
                var budget = execution.begin();
                long clock = System.nanoTime();
                var proposal = ObservationIntentInterpreter.interpret(ai, text, budget);
                long elapsed = (System.nanoTime() - clock) / 1_000_000;
                if (stoppedOn.contains(proposal.reason())) fixture.halt();
                var wire = new LinkedHashMap<>(fixture.current());
                var row = blank(id, "COMPLETED");
                int attempts = number(wire, "supplier_attempts");
                row.put("attempts", attempts);
                row.put("request_bytes", attempts == 0 ? 0 : number(wire, "request_bytes"));
                row.put("elapsed_ms", elapsed);
                if (attempts > 0) {
                    for (String key : List.of("input_tokens", "output_tokens", "response_model", "finish_reason",
                            "response_text", "tool_call_count")) row.put(key, wire.get(key));
                }
                row.put("effective_kind", proposal.kind().name());
                row.put("reason", proposal.reason());
                records.add(row);
                wire.put("interpreter_kind", proposal.kind().name());
                wire.put("interpreter_reason", proposal.reason());
                wire.put("model_budget_calls", budget.modelCalls());
                wire.put("tool_budget_calls", budget.toolCalls());
                wire.put("budget_stopped_code", budget.stoppedCode());
                wire.put("production_input_bytes", external.currentBytes());
                // A fixture response delayed past the budget is not evidence of a received response.
                Path rawPath = output.resolve("raw").resolve(id + ".json");
                writeNew(rawPath, wire);
                var item = new LinkedHashMap<String, Object>();
                item.put("id", id);
                item.put("raw_path", "raw/" + id + ".json");
                item.put("raw_sha256", hash(rawPath));
                evidence.add(item);
                if (stoppedOn.contains(proposal.reason())) {
                    stoppedReason = proposal.reason();
                    stoppedId = id;
                }
            }
        } finally {
            execution.close();
        }
        fixtureSummary = fixture.snapshot();
        boolean closed = listenerClosed(loopback);
        require(closed, "LISTENER_LEAK");
        Map<String, Object> ledgerChecks = LoopbackFixture.budgetSelfTest();
        String manifestHash = hash(assetRoot.resolve("manifest.json"));
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", 1);
        result.put("execution", executionKind);
        result.put("run_id", output.getFileName().toString());
        result.put("manifest_sha256", manifestHash);
        result.put("requested_model", model);
        result.put("records", records);
        run.put("schema_version", 1);
        run.put("execution", executionKind);
        run.put("mode", mode);
        run.put("evidence_label", real ? "Real model run; quality must be assessed from fixed-set results"
                : "Loopback protocol and budget fixture only; no real model quality evidence");
        run.put("scenario", scenario);
        int upstreamRequests = number(fixtureSummary, "upstream_requests");
        run.put("upstream_requests", upstreamRequests);
        run.put("real_model_calls", real ? upstreamRequests : 0);
        run.put("database_calls", 0);
        run.put("started_at", started);
        run.put("finished_at", Instant.now().toString());
        run.put("manifest_sha256", manifestHash);
        run.put("frozen_config", config);
        run.put("loopback_base_url", loopback.toString());
        run.put("real_forwarding_implemented", !mode.equals("fixture"));
        run.put("listener_closed", closed);
        run.put("requested_model", model);
        run.put("system_sha256", hash(system.getBytes(StandardCharsets.UTF_8)));
        run.put("schema_sha256", hash(JSON.writeValueAsBytes(config.path("model").path("schema"))));
        run.put("read_retries_setting", 1); // Deliberately exercise production modelOnce despite ordinary retries.
        run.put("case_request_timeout_ms", scenario.equals("timeout") ? 1000 : 5000);
        run.put("fixture_budget", fixtureSummary);
        run.put("production_accounting", external.snapshot());
        run.put("budget_self_tests", ledgerChecks);
        if (!mode.equals("fixture")) run.put("forwarding_policy_tests", ForwardingGateway.policySelfTest(config, system));
        run.put("execution_order", samples.stream().map(s -> s.path("id").asString()).toList());
        run.put("stop_reason", stoppedReason);
        run.put("stop_id", stoppedId);
        run.put("raw_records", evidence);
        writeNew(output.resolve("results.json"), result);
        writeNew(output.resolve("run.json"), run);
        System.out.println("EVALUATION_COMPLETE mode=" + mode + " scenario=" + scenario + " records=" + records.size()
                + " real_model_calls=" + (real ? upstreamRequests : 0));
    }

    private static List<JsonNode> orderedSamples(Path root, JsonNode config) throws Exception {
        var byId = new LinkedHashMap<String, JsonNode>();
        for (String line : Files.readAllLines(root.resolve("cases.jsonl"), StandardCharsets.UTF_8)) {
            JsonNode item = JSON.readTree(line);
            if (item.path("split").asString().equals("semantic")) {
                require(byId.putIfAbsent(item.path("id").asString(), item) == null, "DUPLICATE_CASE");
            }
        }
        require(byId.size() == 36, "SEMANTIC_CASE_COUNT");
        var result = new ArrayList<JsonNode>();
        for (var id : config.path("execution_order").path("compatibility_sentinel_ids")) {
            JsonNode sample = byId.remove(id.asString());
            require(sample != null, "MISSING_SENTINEL");
            result.add(sample);
        }
        result.addAll(byId.values());
        return result;
    }

    private static Map<String, Object> blank(String id, String status) {
        var result = new LinkedHashMap<String, Object>();
        for (String field : RECORD_FIELDS) result.put(field, null);
        result.put("id", id);
        result.put("status", status);
        result.put("attempts", 0);
        result.put("request_bytes", 0);
        return result;
    }

    private static Map<String, Object> skipped(String id, String reason) {
        var result = blank(id, "SKIPPED");
        result.put("skip_reason", reason);
        return result;
    }

    private static StandardEnvironment isolatedEnvironment(int timeout) {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("isolated-evaluation-fixture", Map.of(
                "ASSISTANT_MAX_MODEL_CALLS", 1, "ASSISTANT_MAX_TOOL_CALLS", 1,
                "ASSISTANT_REQUEST_TIMEOUT_MS", timeout, "ASSISTANT_TURN_TIMEOUT_MS", 60000,
                "ASSISTANT_READ_RETRIES", 1, "EXTERNAL_MAX_CONCURRENT", 1)));
        return env;
    }

    static void verifyFrozen(Path root, Path repo) throws Exception {
        JsonNode manifest = JSON.readTree(Files.readString(root.resolve("manifest.json")));
        for (var pair : List.of(Map.entry("asset_files", root), Map.entry("source_files", repo))) {
            JsonNode files = manifest.path(pair.getKey());
            for (String name : files.propertyNames()) {
                Path path = pair.getValue().resolve(name).toRealPath();
                require(path.startsWith(pair.getValue()), "FROZEN_PATH_ESCAPE");
                require(hash(path).equals(files.path(name).asString()), "FROZEN_HASH_MISMATCH");
            }
        }
    }

    private static Path safeOutput(Path repo, Path requested) throws Exception {
        Path allowed = repo.resolve(".local-validation/observation-semantics-eval").toAbsolutePath().normalize();
        Path output = requested.toAbsolutePath().normalize();
        require(output.startsWith(allowed) && !output.equals(allowed), "OUTPUT_OUTSIDE_ISOLATED_ROOT");
        for (Path cursor = output; cursor != null && cursor.startsWith(repo); cursor = cursor.getParent()) {
            require(!Files.isSymbolicLink(cursor), "OUTPUT_SYMLINK_FORBIDDEN");
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS))
                require(cursor.toRealPath().startsWith(repo), "OUTPUT_REAL_PATH_ESCAPE");
        }
        Files.createDirectories(output.resolve("raw"));
        require(!Files.exists(output.resolve("results.json")) && !Files.exists(output.resolve("run.json")), "OUTPUT_ALREADY_USED");
        return output;
    }

    private static boolean listenerClosed(URI uri) throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 200);
            return false;
        } catch (ConnectException closed) {
            return true;
        }
    }

    static String hash(Path path) throws Exception { return hash(Files.readAllBytes(path)); }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static int number(Map<String, Object> value, String name) {
        Object n = value.get(name);
        return n instanceof Number number ? number.intValue() : 0;
    }
    private static void writeNew(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writeValueAsString(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private static void require(boolean valid, String code) {
        if (!valid) throw new IllegalArgumentException(code);
    }

    /** Test-only DB-free replacement. The inherited JDBC/transaction path must never run. */
    private static final class InMemoryBudget extends ExternalCallBudget {
        private final int limit;
        private int attempts;
        private String currentId;
        private final Map<String, Long> bytes = new LinkedHashMap<>();
        InMemoryBudget(Environment env, int limit) {
            super(null, env, new PlatformTransactionManager() {
                public TransactionStatus getTransaction(TransactionDefinition definition) { throw new AssertionError("DATABASE_FORBIDDEN"); }
                public void commit(TransactionStatus status) { throw new AssertionError("DATABASE_FORBIDDEN"); }
                public void rollback(TransactionStatus status) { throw new AssertionError("DATABASE_FORBIDDEN"); }
            });
            this.limit = limit;
        }
        synchronized void expect(String id) { currentId = id; }
        synchronized long currentBytes() { return bytes.getOrDefault(currentId, 0L); }
        synchronized Map<String, Object> snapshot() {
            return Map.of("reserved_calls", attempts, "max_calls", limit,
                    "input_bytes_unit", "ChatRequest.toString UTF8; separate from wire body",
                    "bytes_by_id", new LinkedHashMap<>(bytes), "database_calls", 0);
        }
        @Override public <T> T invoke(String kind, long inputBytes, Callable<T> call) {
            synchronized (this) {
                require(kind.equals("CHAT") && inputBytes > 0 && currentId != null, "EXTERNAL_CALL_CONTRACT");
                if (attempts >= limit) throw new ExternalCallBudget.Limit();
                require(!bytes.containsKey(currentId), "RETRY_FORBIDDEN");
                attempts++;
                bytes.put(currentId, inputBytes);
            }
            try { return call.call(); }
            catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("FIXTURE_CALL_FAILED"); }
        }
    }
}

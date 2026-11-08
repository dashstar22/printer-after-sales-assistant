package local.printer.assistant;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline reachability check against the actual router and deterministic reference resolver.
 * Does not call the interpreter, conversation service, database, or network. The inferred
 * classifier-call expectation proves branch reachability only, not an observed call count.
 */
public final class GateProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-08T12:00:00");

    private GateProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: GateProbe cases.jsonl gold.jsonl config.json");
            System.exit(2);
        }
        Map<String, JsonNode> cases = readRows(Path.of(args[0]));
        Map<String, JsonNode> gold = readRows(Path.of(args[1]));
        JsonNode contexts = JSON.readTree(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8))
                .get("contexts");
        if (contexts == null || !contexts.isObject()) throw new IllegalArgumentException("Missing contexts");
        if (!cases.keySet().equals(gold.keySet())) throw new IllegalArgumentException("Case and gold IDs differ");
        int passed = 0;
        int failed = 0;
        for (var entry : cases.entrySet()) {
            String id = entry.getKey();
            try {
                JsonNode sample = entry.getValue();
                JsonNode expected = gold.get(id);
                String contextName = requiredText(sample, "context");
                JsonNode context = contexts.get(contextName);
                if (context == null || !context.isObject()) throw new IllegalArgumentException("Unknown context " + contextName);
                DialogueState state = state(context);
                String text = requiredText(sample, "text");
                var decision = DialogueRouter.route("eval", 1, 1, "current", text, state,
                        requiredText(context, "target"), NOW, requiredBoolean(context, "semantics_enabled"));
                var query = KnowledgeQueryCompletion.resolve(text, List.of());
                boolean classifierReachable = decision.semantic() && !query.clarification() && query.query() == null;
                equal("expected_router_semantic", requiredBoolean(expected, "expected_router_semantic"), decision.semantic());
                equal("expected_router_reason", requiredText(expected, "expected_router_reason"), decision.reason());
                JsonNode calls = required(expected, "expected_classifier_calls");
                if (!calls.isIntegralNumber() || (calls.asInt() != 0 && calls.asInt() != 1))
                    throw new IllegalArgumentException("expected_classifier_calls must be 0 or 1");
                equal("expected_classifier_calls (inferred)", calls.asInt(), classifierReachable ? 1 : 0);
                equal("expected_extra_after_router", nullableText(expected, "expected_extra_after_router"),
                        decision.state() == null ? null : decision.state().extra());
                equal("expected_phase_after_router", nullableText(expected, "expected_phase_after_router"),
                        decision.state() == null ? null : decision.state().phase());
                System.out.println("PASS " + id + " router=" + decision.reason()
                        + " classifier_reachable=" + classifierReachable);
                passed++;
            } catch (RuntimeException | AssertionError failure) {
                System.out.println("FAIL " + id + " " + failure.getMessage());
                failed++;
            }
        }
        System.out.println("SUMMARY pass=" + passed + " fail=" + failed + " total=" + cases.size()
                + " model_execution=not_performed; inferred reachability only");
        if (failed != 0) System.exit(1);
    }

    private static DialogueState state(JsonNode context) {
        if (!requiredBoolean(context, "state_present")) return null;
        JsonNode offset = required(context, "expires_offset_seconds");
        if (!offset.isIntegralNumber()) throw new IllegalArgumentException("expires_offset_seconds must be an integer");
        return new DialogueState("eval", 1, 1, requiredText(context, "phase"),
                nullableText(context, "symptom"), nullableText(context, "extra"),
                nullableText(context, "contact"), nullableText(context, "contactMethod"),
                nullableText(context, "pending_field"), "authorized", NOW.plusSeconds(offset.asLong()), 1, "previous");
    }

    private static Map<String, JsonNode> readRows(Path path) throws Exception {
        var rows = new LinkedHashMap<String, JsonNode>();
        int number = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            number++;
            if (line.isBlank()) throw new IllegalArgumentException("Blank row " + path + ":" + number);
            JsonNode row = JSON.readTree(line);
            if (!row.isObject()) throw new IllegalArgumentException("Non-object row " + path + ":" + number);
            String id = requiredText(row, "id");
            if (rows.putIfAbsent(id, row) != null) throw new IllegalArgumentException("Duplicate ID " + id);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Empty rows " + path);
        return rows;
    }

    private static JsonNode required(JsonNode value, String key) {
        JsonNode field = value.get(key);
        if (field == null) throw new IllegalArgumentException("Missing " + key);
        return field;
    }

    private static String requiredText(JsonNode value, String key) {
        JsonNode field = required(value, key);
        if (!field.isString() || field.asString().isBlank()) throw new IllegalArgumentException("Expected nonblank text: " + key);
        return field.asString();
    }

    private static String nullableText(JsonNode value, String key) {
        JsonNode field = required(value, key);
        if (field.isNull()) return null;
        if (!field.isString()) throw new IllegalArgumentException("Expected text or null: " + key);
        return field.asString();
    }

    private static boolean requiredBoolean(JsonNode value, String key) {
        JsonNode field = required(value, key);
        if (!field.isBoolean()) throw new IllegalArgumentException("Expected boolean: " + key);
        return field.asBoolean();
    }

    private static void equal(String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
    }
}

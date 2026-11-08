package local.printer.assistant;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly gated real entry, default use is local forwarding verification. */
public final class ObservationE3Adapter {
    static final Set<String> SCENARIOS = Set.of("success", "invalid-json", "second-sentinel-invalid", "third-sentinel-invalid",
            "tool-response", "finish-length", "timeout", "http-401", "http-429", "http-503", "redirect", "disconnect",
            "call-budget", "byte-budget", "total-byte-budget", "credential-echo");
    static final String SYNTHETIC_KEY = "e3-synthetic-key-never-real";
    private ObservationE3Adapter() {}

    public static void main(String[] args) {
        try {
            if (args.length != 5 || !Set.of("forward-fixture", "real").contains(args[2]) || !SCENARIOS.contains(args[3]))
                throw new IllegalArgumentException("EXPLICIT_MODE_REQUIRED");
            Path assets = Path.of(args[0]).toRealPath();
            Path repo = assets.getParent().getParent().toRealPath();
            ObservationEvalAdapter.verifyFrozen(assets, repo);
            Path output = Path.of(args[1]);
            String mode = args[2], scenario = args[3];
            if (mode.equals("real")) {
                // No network object or credential read until explicit real mode, exact frozen
                // manifest acknowledgment and the one legal real scenario have all passed.
                if (!scenario.equals("success") || !args[4].equals(ObservationEvalAdapter.hash(assets.resolve("manifest.json"))))
                    throw new IllegalArgumentException("REAL_MANIFEST_ACK_REQUIRED");
                var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String key = input.readLine();
                if (key == null || key.length() < 8 || key.length() > 4096 || !key.matches("[\\x21-\\x7E]+"))
                    throw new IllegalArgumentException("REAL_PRIVATE_STDIN_KEY_REQUIRED");
                ObservationEvalAdapter.runWithEndpoint(assets, output, scenario, mode,
                        (config, system, calls, bytes, total) -> ForwardingGateway.real(config, system, key));
            } else {
                if (!args[4].equals("-")) throw new IllegalArgumentException("FIXTURE_HAS_NO_REAL_ACK");
                ForwardingUpstreamFixture upstream = new ForwardingUpstreamFixture(scenario, SYNTHETIC_KEY);
                try (upstream) {
                    ObservationEvalAdapter.runWithEndpoint(assets, output, scenario, mode,
                            (config, system, calls, bytes, total) -> ForwardingGateway.test(config, system, upstream.endpoint(),
                                    SYNTHETIC_KEY, calls, bytes, scenario.equals("total-byte-budget")
                                            ? (config.path("asset_version").asString().equals("observation-semantics-fixed-v2") ? 4096 : 1600)
                                            : total));
                }
                Files.writeString(output.resolve("upstream.json"), JsonMapper.builder().build().writeValueAsString(upstream.snapshot()) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
        } catch (Exception | AssertionError failure) {
            System.err.println("E3_ADAPTER_FAILED: " + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }
}

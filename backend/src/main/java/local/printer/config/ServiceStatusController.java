package local.printer.config;

import local.printer.assistant.AssistantTraceFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated display metadata only. No URL, credential, model name or identity input. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServiceStatusController {
    private final DependencyProperties dependencies;
    private final Environment environment;
    public ServiceStatusController(DependencyProperties dependencies, Environment environment) {
        this.dependencies = dependencies; this.environment = environment;
    }
    public record Status(String assistantMode, String deploymentMode) {}
    @GetMapping("/api/service-status")
    ResponseEntity<Status> status() {
        String mode = switch (AssistantTraceFilter.modelMode(dependencies.ai(), environment.getProperty("ASSISTANT_MODEL_MODE", "AUTO"))) {
            case "DISABLED" -> "PERSISTENCE_ONLY";
            case "PROTOCOL_SIMULATION" -> "SIMULATED_API";
            default -> "MODEL_API";
        };
        String deployment = switch (environment.getProperty("PORTFOLIO_MODE", "")) {
            case "real" -> "PORTFOLIO_REAL";
            case "offline" -> "PORTFOLIO_OFFLINE";
            default -> "STANDARD";
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Status(mode, deployment));
    }
}

package local.printer.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/auth/register")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RegistrationController {
    private final RegistrationService service;
    public RegistrationController(RegistrationService service) { this.service = service; }
    @PostMapping
    ResponseEntity<RegistrationService.Registered> register(@AuthenticationPrincipal SessionUser user, @RequestBody JsonNode body) {
        if (user != null) throw new AlreadySignedIn();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.register(body));
    }
    static final class AlreadySignedIn extends RuntimeException {}
}

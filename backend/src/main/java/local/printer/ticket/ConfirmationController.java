package local.printer.ticket;

import local.printer.auth.SessionUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.Set;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConfirmationController {
    private final ConfirmationService service;
    public ConfirmationController(ConfirmationService service) { this.service = service; }

    @PostMapping("/api/repair-drafts/{id}/confirmations")
    ResponseEntity<ConfirmationResponse> confirm(@AuthenticationPrincipal SessionUser user, @PathVariable String id,
                                                 @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !body.propertyNames().equals(Set.of("confirmed", "expectedVersion")))
            throw new DraftService.InvalidInput();
        var action = body.get("confirmed"); var version = body.get("expectedVersion");
        if (!action.isBoolean() || !action.asBoolean() || !version.isIntegralNumber()
                || !version.canConvertToLong() || version.asLong() < 0) throw new DraftService.InvalidInput();
        var result = service.confirm(user.identity().id(), DraftController.id(id), version.asLong());
        return ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore()).body(result.response());
    }
}

package local.printer.ticket;

import local.printer.auth.SessionUser;
import local.printer.assistant.AssistantAudit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/ticket-operations")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TicketOperationController {
    private final TicketOperationService service;
    private final TicketExecutionCoordinator coordinator;
    public TicketOperationController(TicketOperationService service, TicketExecutionCoordinator coordinator) { this.service = service; this.coordinator = coordinator; }
    @GetMapping("/{operationId}")
    ResponseEntity<TicketOperationResponse> detail(@AuthenticationPrincipal SessionUser user, @PathVariable String operationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(user.identity().id(), TicketOperationInput.uuid(operationId)));
    }
    @PostMapping("/{operationId}/execute")
    ResponseEntity<TicketOperationResponse> execute(@AuthenticationPrincipal SessionUser user, @PathVariable String operationId,
                                                   @RequestBody JsonNode body) {
        var input = TicketOperationInput.parse(body);
        var result = coordinator.execute(user.identity().id(), TicketOperationInput.uuid(operationId), input);
        AssistantAudit.result(result.response().status(),!result.created());
        return ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore()).body(result.response());
    }
}

package local.printer.assistant;

import local.printer.auth.SessionUser;
import local.printer.ticket.TicketOperationInput;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/conversations/{id}/ticket-operations")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConversationTicketController {
    private final ConversationTicketCoordinator coordinator;
    public ConversationTicketController(ConversationTicketCoordinator coordinator) { this.coordinator=coordinator; }
    @PostMapping("/{operationId}/execute")
    ResponseEntity<ConversationTicketCoordinator.Result> execute(@AuthenticationPrincipal SessionUser user,
            @PathVariable String id,@PathVariable String operationId,@RequestBody JsonNode body) {
        var result=coordinator.execute(user.identity().id(),id,TicketOperationInput.uuid(operationId),TicketOperationInput.parse(body));
        AssistantAudit.result(result.response().status(),"NOT_REQUESTED_ON_REPLAY".equals(result.followupStatus()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}

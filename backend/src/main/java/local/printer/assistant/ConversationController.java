package local.printer.assistant;

import java.util.Set;
import local.printer.auth.SessionUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import local.printer.common.ApiResponses;

@RestController
@RequestMapping("/api/conversations")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConversationController {
    private final ConversationService service;
    private final AssistantConversationService assistant;
    public ConversationController(ConversationService service, AssistantConversationService assistant) { this.service = service; this.assistant = assistant; }
    @PostMapping
    ResponseEntity<ConversationResponse.Detail> create(@AuthenticationPrincipal SessionUser user, @RequestBody JsonNode body) {
        fields(body, Set.of("orderId"));
        var order = body.get("orderId");
        if (!order.isIntegralNumber() || !order.canConvertToLong() || order.asLong() < 1) throw new ConversationService.InvalidInput();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.create(user.identity().id(), order.asLong()));
    }
    @GetMapping("/{id}")
    ResponseEntity<ConversationResponse.Detail> detail(@AuthenticationPrincipal SessionUser user, @PathVariable String id,
                                                       @RequestParam MultiValueMap<String, String> params) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(user.identity().id(), id,
            page(params, "page", 1, Integer.MAX_VALUE), page(params, "size", 20, 100)));
    }
    @PostMapping("/{id}/messages")
    ResponseEntity<ConversationResponse.Turn> submit(@AuthenticationPrincipal SessionUser user, @PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        fields(body, Set.of("turnId", "content"));
        if (!body.get("turnId").isString() || !body.get("content").isString()) throw new ConversationService.InvalidInput();
        var result = assistant.enabled()
            ? assistant.submit(user.identity().id(), id, body.get("turnId").asString(), body.get("content").asString())
            : service.submit(user.identity().id(), id, body.get("turnId").asString(), body.get("content").asString());
        AssistantAudit.result(result.assistantMessage().response().status(),result.replayed());
        return ResponseEntity.status(result.replayed() ? 200 : 201).cacheControl(CacheControl.noStore()).header("X-Trace-Id",ApiResponses.traceId(request)).body(result);
    }
    private static void fields(JsonNode body, Set<String> names) {
        if (body == null || !body.isObject() || !body.propertyNames().equals(names)) throw new ConversationService.InvalidInput();
    }
    private static int page(MultiValueMap<String,String> params, String name, int fallback, int max) {
        var values = params.get(name);
        if (values == null) return fallback;
        if (values.size() != 1 || !values.get(0).matches("[0-9]+")) throw new ConversationService.InvalidInput();
        try {
            long value = Long.parseLong(values.get(0));
            if (value < 1 || value > max) throw new ConversationService.InvalidInput();
            return (int) value;
        } catch (NumberFormatException failure) { throw new ConversationService.InvalidInput(); }
    }
}

package local.printer.ticket;

import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import local.printer.auth.SessionUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/tickets")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TicketHandlingController {
    // 只在三个客服入口拒绝重复字段/多根JSON，不改变既有接口的ObjectMapper。
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final TicketHandlingService service;
    public TicketHandlingController(TicketHandlingService service) { this.service = service; }
    @PostMapping("/{id}/accept")
    ResponseEntity<TicketHandlingResponse> accept(@AuthenticationPrincipal SessionUser user, @PathVariable String id, @RequestBody String body, HttpServletRequest request) {
        return handle(user, id, body, TicketHandlingService.Action.ACCEPT, request.getContentType());
    }
    @PostMapping("/{id}/notes")
    ResponseEntity<TicketHandlingResponse> notes(@AuthenticationPrincipal SessionUser user, @PathVariable String id, @RequestBody String body, HttpServletRequest request) {
        return handle(user, id, body, TicketHandlingService.Action.NOTE, request.getContentType());
    }
    @PostMapping("/{id}/close")
    ResponseEntity<TicketHandlingResponse> close(@AuthenticationPrincipal SessionUser user, @PathVariable String id, @RequestBody String body, HttpServletRequest request) {
        return handle(user, id, body, TicketHandlingService.Action.CLOSE, request.getContentType());
    }
    public static class UnsupportedMedia extends RuntimeException {}
    private ResponseEntity<TicketHandlingResponse> handle(SessionUser user, String id, String body, TicketHandlingService.Action action, String contentType) {
        try {
            var media = MediaType.parseMediaType(contentType == null ? "" : contentType);
            if (!"application".equals(media.getType()) || !"json".equals(media.getSubtype())) throw new UnsupportedMedia();
        } catch (org.springframework.http.InvalidMediaTypeException invalid) { throw new UnsupportedMedia(); }
        JsonNode value;
        try { value = JSON.readTree(body); }
        catch (JacksonException invalid) { throw new DraftService.InvalidInput(); }
        var fields = action == TicketHandlingService.Action.ACCEPT ? Set.of("expectedVersion") : Set.of("expectedVersion", "note");
        if (value == null || !value.isObject() || !value.propertyNames().equals(fields)) throw new DraftService.InvalidInput();
        var version = value.get("expectedVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() < 0) throw new DraftService.InvalidInput();
        String note = null;
        if (action != TicketHandlingService.Action.ACCEPT) {
            var text = value.get("note");
            if (!text.isString()) throw new DraftService.InvalidInput();
            note = text.asString();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.handle(user.identity(), DraftController.id(id), version.asLong(), action, note));
    }
}

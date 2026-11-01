package local.printer.ticket;

import local.printer.auth.SessionUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/repair-drafts")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DraftController {
    private final DraftService service;
    public DraftController(DraftService service) { this.service = service; }

    @PostMapping
    ResponseEntity<DraftResponse> create(@AuthenticationPrincipal SessionUser user, @RequestBody JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(service.create(user.identity().id(), input(body, false)));
    }
    @GetMapping("/{id}")
    ResponseEntity<DraftResponse> detail(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(user.identity().id(), id(id)));
    }
    @PutMapping("/{id}")
    ResponseEntity<DraftResponse> update(@AuthenticationPrincipal SessionUser user, @PathVariable String id, @RequestBody JsonNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(user.identity().id(), id(id), input(body, true)));
    }
    private static DraftService.Input input(JsonNode body, boolean update) {
        if (body == null || !body.isObject()) throw new DraftService.InvalidInput();
        return new DraftService.Input(number(body.get("orderId"), 1), text(body.get("symptom")), text(body.get("extra")),
            text(body.get("contact")), text(body.get("contactMethod")), update ? number(body.get("expectedVersion"), 0) : null);
    }
    private static String text(JsonNode value) {
        if (value == null || !value.isString()) throw new DraftService.InvalidInput();
        return value.asString();
    }
    private static long number(JsonNode value, long min) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < min) throw new DraftService.InvalidInput();
        return value.asLong();
    }
    static long id(String value) {
        if (!value.matches("[0-9]+")) throw new DraftService.InvalidInput();
        try {
            long id = Long.parseLong(value);
            if (id < 1) throw new DraftService.InvalidInput();
            return id;
        } catch (NumberFormatException failure) { throw new DraftService.InvalidInput(); }
    }
}

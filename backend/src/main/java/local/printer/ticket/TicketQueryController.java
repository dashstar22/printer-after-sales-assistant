package local.printer.ticket;

import java.util.Set;
import local.printer.auth.SessionUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TicketQueryController {
    private final TicketQueryService service;
    public TicketQueryController(TicketQueryService service) { this.service = service; }
    @GetMapping
    ResponseEntity<TicketQueryResponse.Page> list(@AuthenticationPrincipal SessionUser user, @RequestParam MultiValueMap<String,String> params) {
        String page = single(params, "page"), size = single(params, "size");
        String status = single(params, "status"), keyword = single(params, "keyword");
        if (status != null && !Set.of("PENDING", "IN_PROGRESS", "CLOSED").contains(status)) throw new DraftService.InvalidInput();
        if (keyword != null) keyword = DraftService.text(keyword, 100);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(user.identity(),
            page == null ? 1 : (int) positive(page, Integer.MAX_VALUE), size == null ? 20 : (int) positive(size, 100), status, keyword));
    }
    @GetMapping("/{id}")
    ResponseEntity<TicketQueryResponse.Detail> detail(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(user.identity(), positive(id, Long.MAX_VALUE)));
    }
    private static String single(MultiValueMap<String,String> params, String key) {
        var values = params.get(key);
        if (values == null) return null;
        if (values.size() != 1 || values.get(0) == null) throw new DraftService.InvalidInput();
        return values.get(0);
    }
    private static long positive(String value, long max) {
        if (value == null || !value.matches("[0-9]+")) throw new DraftService.InvalidInput();
        try {
            long number = Long.parseLong(value);
            if (number < 1 || number > max) throw new DraftService.InvalidInput();
            return number;
        } catch (NumberFormatException invalid) { throw new DraftService.InvalidInput(); }
    }
}

package local.printer.order;

import local.printer.auth.SessionUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }

    @GetMapping
    OrderService.Page list(@AuthenticationPrincipal SessionUser user, @RequestParam MultiValueMap<String, String> params) {
        int page = pageParameter(params, "page", 1, Integer.MAX_VALUE);
        int size = pageParameter(params, "size", 20, 100);
        return service.list(user.identity().id(), page, size);
    }

    @GetMapping("/{id}")
    OrderResponse detail(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
        return service.detail(user.identity().id(), positive(id, Long.MAX_VALUE));
    }

    private static int pageParameter(MultiValueMap<String, String> params, String key, int fallback, int max) {
        var values = params.get(key);
        if (values == null) return fallback;
        if (values.size() != 1) throw new OrderService.InvalidInput();
        return (int) positive(values.get(0), max);
    }

    private static long positive(String value, long max) {
        if (value == null || !value.matches("[0-9]+")) throw new OrderService.InvalidInput();
        try {
            long number = Long.parseLong(value);
            if (number < 1 || number > max) throw new OrderService.InvalidInput();
            return number;
        } catch (NumberFormatException failure) { throw new OrderService.InvalidInput(); }
    }
}

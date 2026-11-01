package local.printer.order;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {
    private final OrderMapper mapper;
    public OrderService(OrderMapper mapper) { this.mapper = mapper; }
    public record Page(List<OrderResponse> items, int page, int size, long total, long totalPages) {}
    public static class NotFound extends RuntimeException {}
    public static class InvalidInput extends RuntimeException {}

    public Page list(long customerId, int page, int size) {
        long total = mapper.countOwned(customerId);
        long offset = ((long) page - 1) * size;
        return new Page(mapper.listOwned(customerId, offset, size), page, size, total,
            total == 0 ? 0 : (total - 1) / size + 1);
    }

    public OrderResponse detail(long customerId, long id) {
        var order = mapper.findOwned(id, customerId);
        if (order == null) throw new NotFound();
        return order;
    }
}

package local.printer.ticket;

import java.time.ZoneOffset;
import local.printer.auth.SessionUser;
import local.printer.order.OrderMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TicketQueryService {
    private final TicketQueryMapper mapper;
    private final OrderMapper orders;
    public TicketQueryService(TicketQueryMapper mapper, OrderMapper orders) { this.mapper = mapper; this.orders = orders; }
    public static class NotFound extends RuntimeException {}

    private static Long scope(SessionUser.Identity identity) {
        return switch (identity.role()) {
            case "CUSTOMER" -> identity.id();
            case "STAFF" -> null;
            default -> throw new AccessDeniedException("没有执行此操作的权限");
        };
    }
    public TicketQueryResponse.Page list(SessionUser.Identity identity, int page, int size, String status, String keyword) {
        Long customer = scope(identity);
        String pattern = keyword == null ? null : "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long total = mapper.count(customer, status, pattern);
        return new TicketQueryResponse.Page(mapper.list(customer, status, pattern, ((long) page - 1) * size, size),
            page, size, total, total == 0 ? 0 : (total - 1) / size + 1);
    }
    public TicketQueryResponse.Detail detail(SessionUser.Identity identity, long id) {
        Long customer = scope(identity);
        var row = mapper.detail(id, customer);
        if (row == null) throw new NotFound();
        var order = orders.findOwned(row.orderId(), row.customerId());
        var events = mapper.events(id, customer).stream().map(e -> new TicketQueryResponse.Event(e.id(), e.actorId(),
            e.eventType(), e.note(), e.createdAt().toInstant(ZoneOffset.UTC))).toList();
        return new TicketQueryResponse.Detail(row.id(), row.ticketNo(), row.orderId(), row.status(), row.version(), row.acceptedBy(),
            row.snapshotSymptom(), row.snapshotExtra(), row.snapshotContact(), row.snapshotContactMethod(), order, events);
    }
}

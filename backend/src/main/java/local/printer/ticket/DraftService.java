package local.printer.ticket;

import local.printer.order.OrderMapper;
import local.printer.order.OrderResponse;
import local.printer.order.OrderService;
import local.printer.ticket.TicketRows.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DraftService {
    private final TicketMapper mapper;
    private final OrderMapper orders;
    public DraftService(TicketMapper mapper, OrderMapper orders) { this.mapper = mapper; this.orders = orders; }

    public record Input(long orderId, String symptom, String extra, String contact, String contactMethod, Long expectedVersion) {
        @Override public String toString() { return "DraftInput[REDACTED]"; }
    }
    public static class InvalidInput extends RuntimeException {}
    public static class NotFound extends RuntimeException {}
    public static class Conflict extends RuntimeException {}
    public static class AlreadySubmitted extends RuntimeException {}

    @Transactional
    public DraftResponse create(long customerId, Input input) {
        var row = normalized(customerId, input, 0);
        var order = activeOrder(customerId, row.orderId());
        var value = new NewRow<>(row);
        if (mapper.insertDraft(value) != 1) throw new Conflict();
        return response(mapper.ownedDraft(value.getId(), customerId), order);
    }

    @Transactional(readOnly = true)
    public DraftResponse detail(long customerId, long id) {
        var row = mapper.ownedDraft(id, customerId);
        if (row == null) throw new NotFound();
        return response(row, ownedOrder(customerId, row.orderId()));
    }

    @Transactional
    public DraftResponse update(long customerId, long id, Input input) {
        // 与后续确认/建单共同约定：先草稿锁，再检查关联；锁到事务结束。
        var current = mapper.lockOwnedDraft(id, customerId);
        if (current == null) throw new NotFound();
        if (mapper.linkedTicket(id) != null) throw new AlreadySubmitted();
        if (input.expectedVersion() == null || input.expectedVersion() < 0) throw new InvalidInput();
        if (input.expectedVersion() != current.version() || current.version() == Long.MAX_VALUE) throw new Conflict();
        var next = normalized(customerId, input, current.version());
        var order = activeOrder(customerId, next.orderId());
        if (mapper.updateOwnedDraft(id, next) != 1) throw new Conflict();
        return response(mapper.ownedDraft(id, customerId), order);
    }

    private OrderResponse ownedOrder(long customerId, long id) {
        var order = orders.findOwned(id, customerId);
        if (order == null) throw new OrderService.NotFound();
        return order;
    }
    private OrderResponse activeOrder(long customerId,long id) {
        var order=orders.lockActiveOwned(id,customerId);
        if(order==null)throw new OrderService.NotFound();return order;
    }
    private static Draft normalized(long customerId, Input input, long version) {
        if (input.orderId() <= 0) throw new InvalidInput();
        return new Draft(null, customerId, input.orderId(), text(input.symptom(), 2000), text(input.extra(), 2000),
            text(input.contact(), 100), text(input.contactMethod(), 200), version);
    }
    private static boolean whitespace(int codepoint) {
        return Character.isWhitespace(codepoint) || Character.isSpaceChar(codepoint);
    }
    static String text(String value, int max) {
        if (value == null) throw new InvalidInput();
        int start = 0, end = value.length();
        while (start < end && whitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && whitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        var normalized = value.substring(start, end);
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > max) throw new InvalidInput();
        return normalized;
    }
    private static DraftResponse response(Draft row, OrderResponse order) {
        return new DraftResponse(row.id(), row.orderId(), row.symptom(), row.extra(), row.contact(), row.contactMethod(), row.version(), order);
    }
}

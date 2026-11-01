package local.printer.ticket;

import java.time.Instant;
import java.util.List;
import local.printer.order.OrderResponse;

/** Explicit read DTOs; persistence/confirmation rows are never serialized. */
public final class TicketQueryResponse {
    private TicketQueryResponse() {}
    public record Item(long id, String ticketNo, long orderId, String orderNo, String modelCode, String status, long version) {}
    public record Page(List<Item> items, int page, int size, long total, long totalPages) {}
    public record Event(long id, long actorId, String eventType, String note, Instant createdAt) {}
    public record Detail(long id, String ticketNo, long orderId, String status, long version, Long acceptedBy,
                         String snapshotSymptom, String snapshotExtra, String snapshotContact, String snapshotContactMethod,
                         OrderResponse order, List<Event> events) {}
}

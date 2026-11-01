package local.printer.ticket;

import java.time.LocalDateTime;

/** Internal persistence types, never HTTP DTOs. Times use UTC, at microsecond precision. */
public final class TicketRows {
    private TicketRows() {}

    public record Draft(Long id, long customerId, long orderId, String symptom, String extra,
                        String contact, String contactMethod, long version) {
        @Override public String toString() { return "Draft[REDACTED]"; }
    }
    public record Confirmation(String id, long draftId, long customerId, long draftVersion,
                               String payloadHash, LocalDateTime expiresAt, String operationId, Long ticketId) {
        @Override public String toString() { return "Confirmation[REDACTED]"; }
    }
    /** Snapshot columns are copied on insert, not joined back to the mutable draft. */
    public record Ticket(Long id, String ticketNo, long draftId, long customerId, long orderId,
                         String snapshotSymptom, String snapshotExtra, String snapshotContact,
                         String snapshotContactMethod, String status, long version, Long acceptedBy) {
        @Override public String toString() { return "Ticket[REDACTED]"; }
    }
    public record Event(Long id, long ticketId, long actorId, String eventType, String note, LocalDateTime createdAt) {
        @Override public String toString() { return "Event[REDACTED]"; }
    }

    /** JDBC generated-key receiver; immutable row records cannot receive a generated id. */
    public static final class NewRow<T> {
        private Long id;
        private final T row;
        public NewRow(T row) { this.row = row; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public T getRow() { return row; }
        @Override public String toString() { return "NewRow[REDACTED]"; }
    }
}

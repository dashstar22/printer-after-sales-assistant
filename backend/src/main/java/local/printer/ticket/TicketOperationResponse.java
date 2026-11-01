package local.printer.ticket;

import java.time.Instant;

/** Minimal operation recovery result; ticket detail and timeline belong to T015. */
public record TicketOperationResponse(String operationId, String confirmationId, long draftId, long draftVersion,
                                      String status, Instant expiresAt, TicketResult ticket) {
    public record TicketResult(long id, String ticketNo, String status, long version) {}
    @Override public String toString() { return "TicketOperationResponse[REDACTED]"; }
}

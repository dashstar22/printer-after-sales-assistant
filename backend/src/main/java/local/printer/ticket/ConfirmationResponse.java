package local.printer.ticket;

import java.time.Instant;
import java.time.ZoneOffset;
import local.printer.ticket.TicketRows.Confirmation;

public record ConfirmationResponse(String confirmationId, String operationId, long draftId, long draftVersion,
                                   Instant expiresAt, Long ticketId) {
    static ConfirmationResponse from(Confirmation row) {
        return new ConfirmationResponse(row.id(), row.operationId(), row.draftId(), row.draftVersion(),
            row.expiresAt().toInstant(ZoneOffset.UTC), row.ticketId());
    }
    @Override public String toString() { return "ConfirmationResponse[REDACTED]"; }
}

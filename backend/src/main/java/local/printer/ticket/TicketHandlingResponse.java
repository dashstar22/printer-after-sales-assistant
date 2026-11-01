package local.printer.ticket;

public record TicketHandlingResponse(long id, String status, long version, Long acceptedBy, TicketQueryResponse.Event event) {}

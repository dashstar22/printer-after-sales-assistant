package local.printer.ticket;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import local.printer.auth.SessionUser;
import local.printer.ticket.TicketRows.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketHandlingService {
    private final TicketHandlingMapper handling;
    private final TicketMapper tickets;
    private final Clock clock;
    public TicketHandlingService(TicketHandlingMapper handling, TicketMapper tickets, Clock clock) {
        this.handling = handling; this.tickets = tickets; this.clock = clock;
    }
    public enum Action { ACCEPT, NOTE, CLOSE }
    public static class Conflict extends RuntimeException {}

    @Transactional
    public TicketHandlingResponse handle(SessionUser.Identity actor, long id, long expectedVersion, Action action, String note) {
        if (!"STAFF".equals(actor.role())) throw new AccessDeniedException("没有执行此操作的权限");
        if (id < 1 || expectedVersion < 0 || action == null) throw new DraftService.InvalidInput();
        String text = action == Action.ACCEPT ? "客服接单" : DraftService.text(note, 2000);
        var row = handling.lock(id);
        if (row == null) throw new TicketQueryService.NotFound();
        String expectedStatus = action == Action.ACCEPT ? "PENDING" : "IN_PROGRESS";
        if (!expectedStatus.equals(row.status()) || expectedVersion != row.version() || row.version() == Long.MAX_VALUE) throw new Conflict();
        String nextStatus = action == Action.CLOSE ? "CLOSED" : "IN_PROGRESS";
        Long acceptedBy = action == Action.ACCEPT ? actor.id() : row.acceptedBy();
        if (handling.advance(id, expectedStatus, expectedVersion, nextStatus, acceptedBy) != 1) throw new Conflict();
        var time = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        String type = switch (action) { case ACCEPT -> "ACCEPTED"; case NOTE -> "NOTE"; case CLOSE -> "CLOSED"; };
        var event = new NewRow<>(new Event(null, id, actor.id(), type, text, time));
        if (tickets.insertEvent(event) != 1) throw new Conflict();
        return new TicketHandlingResponse(id, nextStatus, row.version() + 1, acceptedBy,
            new TicketQueryResponse.Event(event.getId(), actor.id(), type, text, time.toInstant(ZoneOffset.UTC)));
    }
}

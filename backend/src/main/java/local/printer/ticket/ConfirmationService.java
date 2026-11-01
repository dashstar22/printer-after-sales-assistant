package local.printer.ticket;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import local.printer.order.OrderMapper;
import local.printer.order.OrderService;
import local.printer.ticket.TicketRows.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmationService {
    private final TicketMapper mapper;
    private final OrderMapper orders;
    private final Clock clock;
    public ConfirmationService(TicketMapper mapper, OrderMapper orders, Clock clock) {
        this.mapper = mapper; this.orders = orders; this.clock = clock;
    }
    public record Result(ConfirmationResponse response, boolean created) {}
    public static class NotFound extends RuntimeException {}
    public static class Conflict extends RuntimeException {}
    public static class Expired extends RuntimeException {}

    /** Only the protected explicit HTTP action calls this creation entry; no model tool registration. */
    @Transactional
    public Result confirm(long customerId, long draftId, long expectedVersion) {
        if (expectedVersion < 0) throw new DraftService.InvalidInput();
        var draft = lockDraft(customerId, draftId);
        if (draft.version() != expectedVersion) throw new DraftService.Conflict();
        var hash = ConfirmationPayload.hash(draft);
        var ticketId = mapper.linkedTicket(draftId);
        if (ticketId != null) {
            var ticket = mapper.ticket(ticketId);
            if (ticket.customerId() != customerId || ticket.orderId() != draft.orderId()) throw new Conflict();
            var completed = mapper.completedConfirmation(customerId, draftId, ticketId, draft.version(), hash);
            if (completed == null) throw new Conflict();
            return new Result(ConfirmationResponse.from(completed), false);
        }
        // Read time after acquiring the shared draft lock, never before a potentially long wait.
        var now = now();
        var existing = mapper.reusableConfirmation(customerId, draftId, draft.version(), hash, now);
        if (existing != null) return new Result(ConfirmationResponse.from(existing), false);
        var row = new Confirmation(UUID.randomUUID().toString(), draftId, customerId, draft.version(), hash,
            now.plusMinutes(30), UUID.randomUUID().toString(), null);
        if (mapper.insertConfirmation(row) != 1) throw new Conflict();
        return new Result(ConfirmationResponse.from(row), true);
    }

    /** T014 must call inside its transaction, retain locks and handle committed results BEFORE this pending-only check. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Confirmation validatePending(long customerId, long draftId, String confirmationId, String operationId) {
        return validateUnconsumed(customerId, draftId, confirmationId, operationId, false);
    }

    /** Same binding/expiry checks; T014 may associate a second valid operation with an existing draft ticket. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Confirmation validateUnconsumed(long customerId, long draftId, String confirmationId, String operationId) {
        return validateUnconsumed(customerId, draftId, confirmationId, operationId, true);
    }

    private Confirmation validateUnconsumed(long customerId, long draftId, String confirmationId, String operationId, boolean allowLinked) {
        var draft = lockDraft(customerId, draftId);
        if (!uuid(confirmationId) || !uuid(operationId)) throw new NotFound();
        var row = mapper.lockOwnedConfirmation(confirmationId, operationId, customerId, draftId);
        if (row == null) throw new NotFound();
        if (row.customerId() != customerId || row.draftId() != draftId || row.draftVersion() != draft.version()
                || !ConfirmationPayload.hash(draft).equals(row.payloadHash())) throw new Conflict();
        if (row.ticketId() != null || (!allowLinked && mapper.linkedTicket(draftId) != null)) throw new Conflict();
        if (!now().isBefore(row.expiresAt())) throw new Expired();
        return row;
    }

    private Draft lockDraft(long customerId, long draftId) {
        var draft = mapper.lockOwnedDraft(draftId, customerId);
        if (draft == null) throw new DraftService.NotFound();
        if (orders.findOwned(draft.orderId(), customerId) == null) throw new OrderService.NotFound();
        return draft;
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private static boolean uuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}

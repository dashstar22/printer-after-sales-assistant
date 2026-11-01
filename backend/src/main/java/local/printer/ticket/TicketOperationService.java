package local.printer.ticket;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import local.printer.order.OrderMapper;
import local.printer.ticket.TicketRows.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TicketOperationService {
    static final String CREATED_NOTE = "客户确认报修并创建工单";
    private final TicketMapper mapper;
    private final OrderMapper orders;
    private final ConfirmationService confirmations;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public TicketOperationService(TicketMapper mapper, OrderMapper orders, ConfirmationService confirmations,
                                  Clock clock, PlatformTransactionManager transactions) {
        this.mapper = mapper; this.orders = orders; this.confirmations = confirmations; this.clock = clock;
        write = new TransactionTemplate(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // The initial operation lookup must not pin an old snapshot while waiting for the draft lock.
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read = new TransactionTemplate(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public record Input(String confirmationId, long draftId, long expectedVersion) {
        @Override public String toString() { return "OperationInput[REDACTED]"; }
    }
    public record Result(TicketOperationResponse response, boolean created) {}
    public static class NotFound extends RuntimeException {}
    public static class Conflict extends RuntimeException {}

    // Intentionally no surrounding @Transactional: duplicate handling starts only AFTER template rollback.
    public Result execute(long customerId, String operationId, Input input) {
        try { return write.execute(status -> executeInTransaction(customerId, operationId, input, false)); }
        catch (DuplicateKeyException duplicate) {
            if (duplicateDraft(duplicate))
                return write.execute(status -> executeInTransaction(customerId, operationId, input, true));
            if (duplicateKey(duplicate, "uk_tickets_no")) throw new Conflict();
            throw duplicate;
        }
    }

    public TicketOperationResponse detail(long customerId, String operationId) {
        return read.execute(status -> {
            var row = ownedOperation(customerId, operationId);
            var draft = mapper.ownedDraft(row.draftId(), customerId);
            binding(customerId, draft, row);
            if (row.ticketId() != null) return response(row, resultTicket(customerId, draft, row.ticketId()));
            return response(row, null);
        });
    }

    private Result executeInTransaction(long customerId, String operationId, Input input, boolean recoverOnly) {
        var located = ownedOperation(customerId, operationId);
        var draft = mapper.lockOwnedDraft(located.draftId(), customerId);
        var row = mapper.lockOwnedConfirmation(located.id(), operationId, customerId, located.draftId());
        if (row == null) throw new NotFound();
        if (!row.id().equals(input.confirmationId()) || row.draftId() != input.draftId()
                || row.draftVersion() != input.expectedVersion()) throw new ConfirmationService.Conflict();
        binding(customerId, draft, row);
        // A committed result is recovered before expiry. Binding and ownership are never skipped.
        if (row.ticketId() != null) return new Result(response(row, resultTicket(customerId, draft, row.ticketId())), false);
        row = confirmations.validateUnconsumed(customerId, draft.id(), row.id(), operationId);
        Long ticketId = mapper.linkedTicket(draft.id());
        boolean created = ticketId == null;
        Ticket ticket;
        if (created) {
            if (recoverOnly) throw new Conflict();
            var value = new NewRow<>(new Ticket(null, "TK-" + UUID.randomUUID(), draft.id(), customerId, draft.orderId(),
                DraftService.text(draft.symptom(), 2000), DraftService.text(draft.extra(), 2000),
                DraftService.text(draft.contact(), 100), DraftService.text(draft.contactMethod(), 200), "PENDING", 0, null));
            if (mapper.insertTicket(value) != 1) throw new Conflict();
            ticketId = value.getId();
            var event = new NewRow<>(new Event(null, ticketId, customerId, "CREATED", CREATED_NOTE, now()));
            if (mapper.insertEvent(event) != 1) throw new Conflict();
            ticket = resultTicket(customerId, draft, ticketId);
        } else ticket = resultTicket(customerId, draft, ticketId);
        if (mapper.associateConfirmation(row, ticketId) != 1) throw new Conflict();
        return new Result(response(row, ticket), created);
    }

    private Confirmation ownedOperation(long customerId, String operationId) {
        var row = mapper.ownedOperation(operationId, customerId);
        if (row == null) throw new NotFound();
        return row;
    }
    private void binding(long customerId, Draft draft, Confirmation row) {
        if (draft == null || row.customerId() != customerId || row.draftId() != draft.id()
                || row.draftVersion() != draft.version() || orders.findOwned(draft.orderId(), customerId) == null)
            throw new ConfirmationService.Conflict();
        try {
            if (!row.payloadHash().equals(ConfirmationPayload.hash(draft))) throw new ConfirmationService.Conflict();
        } catch (DraftService.InvalidInput invalidContent) { throw new ConfirmationService.Conflict(); }
    }
    private Ticket resultTicket(long customerId, Draft draft, long ticketId) {
        var ticket = mapper.ownedTicket(ticketId, customerId, draft.id());
        if (ticket == null || ticket.orderId() != draft.orderId()
                || !ticket.snapshotSymptom().equals(DraftService.text(draft.symptom(), 2000))
                || !ticket.snapshotExtra().equals(DraftService.text(draft.extra(), 2000))
                || !ticket.snapshotContact().equals(DraftService.text(draft.contact(), 100))
                || !ticket.snapshotContactMethod().equals(DraftService.text(draft.contactMethod(), 200)))
            throw new ConfirmationService.Conflict();
        var created = mapper.events(ticketId).stream().filter(event -> "CREATED".equals(event.eventType())).toList();
        if (created.size() != 1 || created.get(0).actorId() != customerId || !CREATED_NOTE.equals(created.get(0).note()))
            throw new Conflict();
        return ticket;
    }
    private TicketOperationResponse response(Confirmation row, Ticket ticket) {
        String state = ticket != null ? "SUCCEEDED" : now().isBefore(row.expiresAt()) ? "READY" : "EXPIRED";
        var result = ticket == null ? null : new TicketOperationResponse.TicketResult(ticket.id(), ticket.ticketNo(), ticket.status(), ticket.version());
        return new TicketOperationResponse(row.operationId(), row.id(), row.draftId(), row.draftVersion(), state,
            row.expiresAt().toInstant(ZoneOffset.UTC), result);
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    static boolean duplicateDraft(Throwable error) { return duplicateKey(error, "uk_tickets_draft"); }
    private static boolean duplicateKey(Throwable error, String constraint) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062 && "23000".equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains("'tickets." + constraint + "'")) return true;
        }
        return false;
    }
}

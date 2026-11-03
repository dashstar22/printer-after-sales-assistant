package local.printer.ticket;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import local.printer.seed.DemoSeedService;
import local.printer.seed.SeedPasswords;
import local.printer.ticket.TicketRows.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL only. All test-method fixtures roll back in the dedicated test schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class TicketPersistenceIT {
    @Autowired TicketMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    long customer, otherCustomer, order, staff;
    final LocalDateTime instant = LocalDateTime.of(2026, 10, 27, 6, 7, 8, 123456000);

    @BeforeAll void isolatedRealDatabase() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("printer_ticket_it_"));
        assertEquals("8.4.13", jdbc.queryForObject("SELECT VERSION()", String.class));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("root@"));
        seed.seed(new SeedPasswords(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        customer = jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_a'", Long.class);
        otherCustomer = jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_b'", Long.class);
        staff = jdbc.queryForObject("SELECT id FROM users WHERE username='demo_staff'", Long.class);
        order = jdbc.queryForObject("SELECT id FROM orders WHERE customer_id=?", Long.class, customer);
    }

    long draft() {
        var value = new NewRow<>(new Draft(null, customer, order, "反复卡纸 🖨", "已取出纸张\n仍报错", "模拟联系人", "仅测试：电话待确认", 7));
        assertEquals(1, mapper.insertDraft(value));
        assertNotNull(value.getId());
        return value.getId();
    }
    Ticket ticketRow(long draft, String number, String status, Long acceptedBy) {
        var source = mapper.draft(draft);
        return new Ticket(null, number, draft, source.customerId(), source.orderId(), source.symptom(), source.extra(),
            source.contact(), source.contactMethod(), status, 9, acceptedBy);
    }
    long ticket(long draft, String number, String status, Long acceptedBy) {
        var value = new NewRow<>(ticketRow(draft, number, status, acceptedBy));
        assertEquals(1, mapper.insertTicket(value));
        assertNotNull(value.getId());
        return value.getId();
    }
    Confirmation confirmation(long draft, String operation, Long ticket) {
        var value = new Confirmation(UUID.randomUUID().toString(), draft, customer, 7, "ab".repeat(32), instant, operation, ticket);
        assertEquals(1, mapper.insertConfirmation(value));
        return value;
    }
    void event(long ticket, LocalDateTime time, String note) {
        var value = new NewRow<>(new Event(null, ticket, customer, "CREATED", note, time));
        assertEquals(1, mapper.insertEvent(value));
        assertNotNull(value.getId());
    }
    void rejected(Class<? extends DataAccessException> type, int code, String state, String constraint, Runnable work) {
        var failure = assertThrows(type, work::run);
        Throwable cause = failure;
        while (!(cause instanceof SQLException) && cause.getCause() != null) cause = cause.getCause();
        assertInstanceOf(SQLException.class, cause);
        var sql = (SQLException) cause;
        assertEquals(code, sql.getErrorCode());
        assertEquals(state, sql.getSQLState());
        assertTrue(sql.getMessage().contains(constraint), "必须匹配指定约束");
    }

    @Test void v1AndV2AreSuccessfulExactlyOnce() {
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank", String.class));
        assertEquals(593940007, jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='1'", Integer.class));
    }

    @Test void allFieldsGeneratedIdsMicrosecondsAndVersionsRoundTrip() {
        long draft = draft();
        assertEquals(new Draft(draft, customer, order, "反复卡纸 🖨", "已取出纸张\n仍报错", "模拟联系人", "仅测试：电话待确认", 7), mapper.draft(draft));
        long ticket = ticket(draft, "T011-ROUNDTRIP", "IN_PROGRESS", staff);
        var expected = ticketRow(draft, "T011-ROUNDTRIP", "IN_PROGRESS", staff);
        assertEquals(new Ticket(ticket, expected.ticketNo(), draft, customer, order, expected.snapshotSymptom(), expected.snapshotExtra(),
            expected.snapshotContact(), expected.snapshotContactMethod(), "IN_PROGRESS", 9, staff), mapper.ticket(ticket));
        var confirmation = confirmation(draft, UUID.randomUUID().toString(), ticket);
        assertEquals(confirmation, mapper.confirmation(confirmation.id()));
        event(ticket, instant.plusSeconds(1), "第二条");
        event(ticket, instant, "第一条");
        event(ticket, instant, "同一微秒按id排序");
        var events = mapper.events(ticket);
        assertEquals(List.of("第一条", "同一微秒按id排序", "第二条"), events.stream().map(Event::note).toList());
        assertEquals(instant, events.get(0).createdAt());
        assertEquals(ticket, events.get(0).ticketId());
        assertEquals(customer, events.get(0).actorId());
        assertEquals("CREATED", events.get(0).eventType());
        assertTrue(events.get(0).id() < events.get(1).id());
        assertTrue(mapper.events(Long.MAX_VALUE).isEmpty());
        assertNull(mapper.draft(Long.MAX_VALUE));
        assertNull(mapper.ticket(Long.MAX_VALUE));
        assertNull(mapper.confirmation(UUID.randomUUID().toString()));
    }

    @Test void nullableResultAndAcceptorRoundTrip() {
        long draft = draft();
        var confirmation = confirmation(draft, UUID.randomUUID().toString(), null);
        assertNull(mapper.confirmation(confirmation.id()).ticketId());
        long ticket = ticket(draft, "T011-NULLABLE", "PENDING", null);
        assertNull(mapper.ticket(ticket).acceptedBy());
    }

    @Test void snapshotDoesNotFollowDraftEdits() {
        long draft = draft();
        long ticket = ticket(draft, "T011-SNAPSHOT", "PENDING", null);
        var before = mapper.ticket(ticket);
        // Test-only SQL proves storage independence; production draft editing remains T012.
        jdbc.update("UPDATE repair_drafts SET symptom='已改变',extra='新现场',contact='新联系人',contact_method='新方式',version=version+1 WHERE id=?", draft);
        assertEquals(8, mapper.draft(draft).version());
        assertEquals(before, mapper.ticket(ticket));
    }

    @ParameterizedTest
    @CsvSource({
        "repair_drafts,customer_id,fk_repair_drafts_customer", "repair_drafts,order_id,fk_repair_drafts_order",
        "repair_confirmations,draft_id,fk_repair_confirmations_draft", "repair_confirmations,customer_id,fk_repair_confirmations_customer",
        "repair_confirmations,ticket_id,fk_repair_confirmations_ticket", "tickets,draft_id,fk_tickets_draft",
        "tickets,customer_id,fk_tickets_customer", "tickets,order_id,fk_tickets_order", "tickets,accepted_by,fk_tickets_accepted_by",
        "ticket_events,ticket_id,fk_ticket_events_ticket", "ticket_events,actor_id,fk_ticket_events_actor"
    })
    void eachForeignKeyRejectsMissingParent(String table, String column, String constraint) {
        long draft = draft();
        long ticket = ticket(draft, "T011-FK", "PENDING", null);
        confirmation(draft, UUID.randomUUID().toString(), ticket);
        event(ticket, instant, "外键验证夹具");
        // Identifiers come exclusively from this fixed test annotation, never client input.
        rejected(DataIntegrityViolationException.class, 1452, "23000", constraint,
            () -> jdbc.update("UPDATE " + table + " SET " + column + "=?", Long.MAX_VALUE));
    }

    @Test void duplicateOperationIdIsRejected() {
        long draft = draft();
        String operation = UUID.randomUUID().toString();
        confirmation(draft, operation, null);
        rejected(DuplicateKeyException.class, 1062, "23000", "uk_repair_confirmations_operation",
            () -> confirmation(draft, operation, null));
    }

    @Test void duplicateTicketNumberIsRejected() {
        ticket(draft(), "T011-UNIQUE-NO", "PENDING", null);
        long otherDraft = draft();
        rejected(DuplicateKeyException.class, 1062, "23000", "uk_tickets_no",
            () -> ticket(otherDraft, "T011-UNIQUE-NO", "PENDING", null));
    }

    @Test void duplicateDraftIsRejected() {
        long draft = draft();
        ticket(draft, "T011-DRAFT-1", "PENDING", null);
        rejected(DuplicateKeyException.class, 1062, "23000", "uk_tickets_draft",
            () -> ticket(draft, "T011-DRAFT-2", "PENDING", null));
    }

    @Test void differentOperationIdsStillCannotCreateTwoTicketsForOneDraft() {
        long draft = draft();
        var first = confirmation(draft, UUID.randomUUID().toString(), null);
        var second = confirmation(draft, UUID.randomUUID().toString(), null);
        assertNotEquals(first.operationId(), second.operationId());
        ticket(draft, "T011-TWO-OPS-1", "PENDING", null);
        rejected(DuplicateKeyException.class, 1062, "23000", "uk_tickets_draft",
            () -> ticket(draft, "T011-TWO-OPS-2", "PENDING", null));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE draft_id=?", Integer.class, draft));
        // This is a uniqueness test, not T014's coordinator, retry or concurrency acceptance.
    }

    @ParameterizedTest @ValueSource(strings = {"INVALID", "pending", "in_progress", "closed", "PENDING ", ""})
    void illegalStatusIsRejectedByCheck(String status) {
        long draft = draft();
        rejected(UncategorizedSQLException.class, 3819, "HY000", "ck_tickets_status",
            () -> ticket(draft, "T011-INVALID", status, null));
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "IN_PROGRESS", "CLOSED"})
    void databaseAcceptsEachDeclaredStatus(String status) {
        long ticket = ticket(draft(), "T011-VALID", status, null);
        assertEquals(status, mapper.ticket(ticket).status());
    }

    @Test void databaseForeignKeysDoNotEnforceOwnershipOrExplicitConfirmation() {
        var draft = new NewRow<>(new Draft(null, otherCustomer, order, "", "", "", "", 0));
        mapper.insertDraft(draft);
        long ticket = ticket(draft.getId(), "T011-BOUNDARY", "PENDING", null);
        assertEquals(otherCustomer, mapper.ticket(ticket).customerId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM repair_confirmations WHERE draft_id=?", Integer.class, draft.getId()));
    }
}

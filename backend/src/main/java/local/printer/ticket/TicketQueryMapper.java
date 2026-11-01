package local.printer.ticket;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** Nullable customer scope is derived only from the trusted STAFF role in the service. */
@Mapper
public interface TicketQueryMapper {
    String FROM = "FROM tickets t JOIN orders o ON o.id=t.order_id JOIN device_models m ON m.id=o.model_id ";
    String SCOPE = "<if test='customerId != null'> AND t.customer_id=#{customerId}</if> ";
    String FILTER = "<where>" + SCOPE + "<if test='status != null'> AND t.status=#{status}</if> "
        + "<if test='pattern != null'> AND (CONVERT(t.ticket_no USING utf8mb4) COLLATE utf8mb4_bin LIKE #{pattern} ESCAPE '!' OR o.order_no LIKE #{pattern} ESCAPE '!' "
        + "OR t.snapshot_symptom LIKE #{pattern} ESCAPE '!')</if></where>";

    @Select("<script>SELECT COUNT(*) " + FROM + FILTER + "</script>")
    long count(@Param("customerId") Long customerId, @Param("status") String status, @Param("pattern") String pattern);

    @Select("<script>SELECT t.id,t.ticket_no AS ticketNo,t.order_id AS orderId,o.order_no AS orderNo,m.model_code AS modelCode,t.status,t.version "
        + FROM + FILTER + " ORDER BY t.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<TicketQueryResponse.Item> list(@Param("customerId") Long customerId, @Param("status") String status,
        @Param("pattern") String pattern, @Param("offset") long offset, @Param("size") int size);

    @Select("<script>SELECT t.id,t.ticket_no AS ticketNo,t.draft_id AS draftId,t.customer_id AS customerId,t.order_id AS orderId,"
        + "t.snapshot_symptom AS snapshotSymptom,t.snapshot_extra AS snapshotExtra,t.snapshot_contact AS snapshotContact,"
        + "t.snapshot_contact_method AS snapshotContactMethod,t.status,t.version,t.accepted_by AS acceptedBy "
        + "FROM tickets t WHERE t.id=#{id} " + SCOPE + "</script>")
    TicketRows.Ticket detail(@Param("id") long id, @Param("customerId") Long customerId);

    @Select("<script>SELECT e.id,e.ticket_id AS ticketId,e.actor_id AS actorId,e.event_type AS eventType,e.note,e.created_at AS createdAt "
        + "FROM ticket_events e JOIN tickets t ON t.id=e.ticket_id WHERE t.id=#{id} " + SCOPE + " ORDER BY e.created_at,e.id</script>")
    List<TicketRows.Event> events(@Param("id") long id, @Param("customerId") Long customerId);
}

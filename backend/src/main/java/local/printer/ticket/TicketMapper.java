package local.printer.ticket;

import java.util.List;
import org.apache.ibatis.annotations.*;
import local.printer.ticket.TicketRows.*;

/** T011 数据层及本人草稿/确认/操作访问；锁和结果关联由应用服务组合进事务。 */
@Mapper
public interface TicketMapper {
    @Select(CONFIRMATION_COLUMNS + "WHERE operation_id=#{operationId} AND customer_id=#{customerId}")
    Confirmation ownedOperation(@Param("operationId") String operationId, @Param("customerId") long customerId);

    @Select("SELECT id,ticket_no AS ticketNo,draft_id AS draftId,customer_id AS customerId,order_id AS orderId,"
        + "snapshot_symptom AS snapshotSymptom,snapshot_extra AS snapshotExtra,snapshot_contact AS snapshotContact,"
        + "snapshot_contact_method AS snapshotContactMethod,status,version,accepted_by AS acceptedBy FROM tickets "
        + "WHERE id=#{id} AND customer_id=#{customerId} AND draft_id=#{draftId}")
    Ticket ownedTicket(@Param("id") long id, @Param("customerId") long customerId, @Param("draftId") long draftId);

    @Update("UPDATE repair_confirmations SET ticket_id=#{ticketId} WHERE id=#{row.id} AND operation_id=#{row.operationId} "
        + "AND customer_id=#{row.customerId} AND draft_id=#{row.draftId} AND draft_version=#{row.draftVersion} "
        + "AND payload_hash=#{row.payloadHash} AND ticket_id IS NULL")
    int associateConfirmation(@Param("row") Confirmation row, @Param("ticketId") long ticketId);

    String CONFIRMATION_COLUMNS = "SELECT id,draft_id AS draftId,customer_id AS customerId,draft_version AS draftVersion,payload_hash AS payloadHash,"
        + "expires_at AS expiresAt,operation_id AS operationId,ticket_id AS ticketId FROM repair_confirmations ";

    @Select(CONFIRMATION_COLUMNS + "WHERE id=#{id} AND operation_id=#{operationId} AND customer_id=#{customerId} AND draft_id=#{draftId} FOR UPDATE")
    Confirmation lockOwnedConfirmation(@Param("id") String id, @Param("operationId") String operationId,
        @Param("customerId") long customerId, @Param("draftId") long draftId);

    @Select(CONFIRMATION_COLUMNS + "WHERE customer_id=#{customerId} AND draft_id=#{draftId} AND draft_version=#{version} "
        + "AND payload_hash=#{hash} AND ticket_id IS NULL AND expires_at>#{now} ORDER BY expires_at DESC,id LIMIT 1 FOR UPDATE")
    Confirmation reusableConfirmation(@Param("customerId") long customerId, @Param("draftId") long draftId,
        @Param("version") long version, @Param("hash") String hash, @Param("now") java.time.LocalDateTime now);

    @Select(CONFIRMATION_COLUMNS + "WHERE customer_id=#{customerId} AND draft_id=#{draftId} AND ticket_id=#{ticketId} "
        + "AND draft_version=#{version} AND payload_hash=#{hash} ORDER BY id LIMIT 1 FOR UPDATE")
    Confirmation completedConfirmation(@Param("customerId") long customerId, @Param("draftId") long draftId,
        @Param("ticketId") long ticketId, @Param("version") long version, @Param("hash") String hash);
    String DRAFT_COLUMNS = "SELECT id,customer_id AS customerId,order_id AS orderId,symptom,extra,contact,contact_method AS contactMethod,version FROM repair_drafts ";

    @Select(DRAFT_COLUMNS + "WHERE id=#{id} AND customer_id=#{customerId}")
    Draft ownedDraft(@Param("id") long id, @Param("customerId") long customerId);

    @Select(DRAFT_COLUMNS + "WHERE id=#{id} AND customer_id=#{customerId} FOR UPDATE")
    Draft lockOwnedDraft(@Param("id") long id, @Param("customerId") long customerId);

    @Select("SELECT id FROM tickets WHERE draft_id=#{draftId} FOR UPDATE")
    Long linkedTicket(long draftId);

    @Update("UPDATE repair_drafts SET order_id=#{row.orderId},symptom=#{row.symptom},extra=#{row.extra},contact=#{row.contact},"
        + "contact_method=#{row.contactMethod},version=version+1 WHERE id=#{id} AND customer_id=#{row.customerId} AND version=#{row.version}")
    int updateOwnedDraft(@Param("id") long id, @Param("row") Draft row);

    @Insert("INSERT INTO repair_drafts(customer_id,order_id,symptom,extra,contact,contact_method,version) "
        + "VALUES(#{row.customerId},#{row.orderId},#{row.symptom},#{row.extra},#{row.contact},#{row.contactMethod},#{row.version})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDraft(NewRow<Draft> value);

    @Select("SELECT id,customer_id AS customerId,order_id AS orderId,symptom,extra,contact,contact_method AS contactMethod,version "
        + "FROM repair_drafts WHERE id=#{id}")
    Draft draft(long id);

    @Insert("INSERT INTO repair_confirmations(id,draft_id,customer_id,draft_version,payload_hash,expires_at,operation_id,ticket_id) "
        + "VALUES(#{id},#{draftId},#{customerId},#{draftVersion},#{payloadHash},#{expiresAt},#{operationId},#{ticketId})")
    int insertConfirmation(Confirmation value);

    @Select("SELECT id,draft_id AS draftId,customer_id AS customerId,draft_version AS draftVersion,payload_hash AS payloadHash,"
        + "expires_at AS expiresAt,operation_id AS operationId,ticket_id AS ticketId FROM repair_confirmations WHERE id=#{id}")
    Confirmation confirmation(String id);

    @Insert("INSERT INTO tickets(ticket_no,draft_id,customer_id,order_id,snapshot_symptom,snapshot_extra,snapshot_contact,snapshot_contact_method,status,version,accepted_by) "
        + "VALUES(#{row.ticketNo},#{row.draftId},#{row.customerId},#{row.orderId},#{row.snapshotSymptom},#{row.snapshotExtra},"
        + "#{row.snapshotContact},#{row.snapshotContactMethod},#{row.status},#{row.version},#{row.acceptedBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTicket(NewRow<Ticket> value);

    @Select("SELECT id,ticket_no AS ticketNo,draft_id AS draftId,customer_id AS customerId,order_id AS orderId,"
        + "snapshot_symptom AS snapshotSymptom,snapshot_extra AS snapshotExtra,snapshot_contact AS snapshotContact,"
        + "snapshot_contact_method AS snapshotContactMethod,status,version,accepted_by AS acceptedBy FROM tickets WHERE id=#{id}")
    Ticket ticket(long id);

    @Insert("INSERT INTO ticket_events(ticket_id,actor_id,event_type,note,created_at) "
        + "VALUES(#{row.ticketId},#{row.actorId},#{row.eventType},#{row.note},#{row.createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEvent(NewRow<Event> value);

    @Select("SELECT id,ticket_id AS ticketId,actor_id AS actorId,event_type AS eventType,note,created_at AS createdAt "
        + "FROM ticket_events WHERE ticket_id=#{ticketId} ORDER BY created_at,id")
    List<Event> events(long ticketId);
}

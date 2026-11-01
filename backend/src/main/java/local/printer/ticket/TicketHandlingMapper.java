package local.printer.ticket;

import org.apache.ibatis.annotations.*;

/** 客服写事务只锁工单，禁止反向获取草稿或确认锁。 */
@Mapper
public interface TicketHandlingMapper {
    @Select("SELECT id,ticket_no AS ticketNo,draft_id AS draftId,customer_id AS customerId,order_id AS orderId,"
        + "snapshot_symptom AS snapshotSymptom,snapshot_extra AS snapshotExtra,snapshot_contact AS snapshotContact,"
        + "snapshot_contact_method AS snapshotContactMethod,status,version,accepted_by AS acceptedBy FROM tickets WHERE id=#{id} FOR UPDATE")
    TicketRows.Ticket lock(long id);

    @Update("UPDATE tickets SET status=#{nextStatus},version=version+1,accepted_by=#{acceptedBy} "
        + "WHERE id=#{id} AND status=#{expectedStatus} AND version=#{expectedVersion} AND version<9223372036854775807")
    int advance(@Param("id") long id, @Param("expectedStatus") String expectedStatus, @Param("expectedVersion") long expectedVersion,
                @Param("nextStatus") String nextStatus, @Param("acceptedBy") Long acceptedBy);
}

package local.printer.assistant;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ConversationMapper {
    record Conversation(String id, long customerId, long orderId, LocalDateTime createdAt) {}
    record Message(long id, String turnId, String role, String content, String responseJson, LocalDateTime createdAt) {
        @Override public String toString() { return "MessageRow[REDACTED]"; }
    }

    @Insert("INSERT INTO conversations(id,customer_id,order_id) VALUES(#{id},#{customerId},#{orderId})")
    int insertConversation(@Param("id") String id, @Param("customerId") long customerId, @Param("orderId") long orderId);

    String OWNED = "SELECT id,customer_id AS customerId,order_id AS orderId,created_at AS createdAt FROM conversations "
        + "WHERE id=#{id} AND customer_id=#{customerId}";
    @Select(OWNED)
    Conversation owned(@Param("id") String id, @Param("customerId") long customerId);
    @Select(OWNED + " FOR UPDATE")
    Conversation lockOwned(@Param("id") String id, @Param("customerId") long customerId);

    String MESSAGES = "SELECT m.id,m.turn_id AS turnId,m.role,m.content,m.response_json AS responseJson,m.created_at AS createdAt "
        + "FROM messages m JOIN conversations c ON c.id=m.conversation_id "
        + "WHERE c.id=#{id} AND c.customer_id=#{customerId} ";
    @Select(MESSAGES + "ORDER BY m.id LIMIT #{size} OFFSET #{offset}")
    List<Message> messages(@Param("id") String id, @Param("customerId") long customerId,
                           @Param("offset") long offset, @Param("size") int size);
    @Select(MESSAGES + "AND m.turn_id=#{turnId} ORDER BY m.id")
    List<Message> turn(@Param("id") String id, @Param("customerId") long customerId, @Param("turnId") String turnId);
    @Select("SELECT COUNT(*) FROM messages m JOIN conversations c ON c.id=m.conversation_id "
        + "WHERE c.id=#{id} AND c.customer_id=#{customerId}")
    long count(@Param("id") String id, @Param("customerId") long customerId);

    @Select(MESSAGES + "AND m.role='ASSISTANT' AND JSON_EXTRACT(m.response_json,'$.draft.id')=#{draftId} ORDER BY m.id DESC LIMIT 1")
    Message preparedDraft(@Param("id") String id, @Param("customerId") long customerId, @Param("draftId") long draftId);

    record DraftAssociation(String draftId) {}
    // Full persisted association set, independent of the bounded model history. Malformed pointers remain visible.
    @Select("SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(m.response_json,'$.draft.id')) AS draftId "
        + "FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE c.id=#{id} AND c.customer_id=#{customerId} "
        + "AND m.role='ASSISTANT' AND (JSON_TYPE(JSON_EXTRACT(m.response_json,'$.draft'))<>'NULL' "
        + "OR JSON_UNQUOTE(JSON_EXTRACT(m.response_json,'$.status'))='DRAFT_PREPARED' "
        + "OR m.response_json IS NULL OR JSON_TYPE(m.response_json)<>'OBJECT') LIMIT 2")
    List<DraftAssociation> draftAssociations(@Param("id") String id, @Param("customerId") long customerId);

    @Select(MESSAGES + "AND m.id<=#{lastId} ORDER BY m.id DESC LIMIT 20")
    List<Message> recent(@Param("id") String id, @Param("customerId") long customerId, @Param("lastId") long lastId);

    @Select(MESSAGES + "AND m.role='USER' ORDER BY m.id DESC LIMIT 1")
    Message latestUser(@Param("id") String id, @Param("customerId") long customerId);

    @Select("SELECT s.conversation_id AS conversationId,s.customer_id AS customerId,s.order_id AS orderId,s.phase,"
        + "s.symptom,s.extra,s.contact,s.contact_method AS contactMethod,s.pending_field AS pendingField,"
        + "s.authorized_turn AS authorizedTurn,s.expires_at AS expiresAt,s.revision,s.last_turn AS lastTurn "
        + "FROM conversation_dialogue_states s JOIN conversations c ON c.id=s.conversation_id "
        + "AND c.customer_id=s.customer_id AND c.order_id=s.order_id "
        + "WHERE c.id=#{id} AND c.customer_id=#{customerId} AND c.order_id=#{orderId}")
    DialogueState dialogueState(@Param("id") String id,@Param("customerId") long customerId,@Param("orderId") long orderId);

    @Select("SELECT CURRENT_TIMESTAMP(6)")
    LocalDateTime dialogueNow();

    @Insert("INSERT INTO conversation_dialogue_states(conversation_id,customer_id,order_id,phase,symptom,extra,contact,contact_method,"
        + "pending_field,authorized_turn,expires_at,revision,last_turn) "
        + "SELECT c.id,c.customer_id,c.order_id,#{s.phase},#{s.symptom},#{s.extra},#{s.contact},#{s.contactMethod},"
        + "#{s.pendingField},#{s.authorizedTurn},#{s.expiresAt},#{s.revision},#{s.lastTurn} FROM conversations c "
        + "WHERE c.id=#{s.conversationId} AND c.customer_id=#{s.customerId} AND c.order_id=#{s.orderId} "
        + "AND EXISTS(SELECT 1 FROM messages m WHERE m.conversation_id=c.id AND m.turn_id=#{s.lastTurn} AND m.role='USER' "
        + "AND m.id=(SELECT MAX(u.id) FROM messages u WHERE u.conversation_id=c.id AND u.role='USER'))")
    int insertDialogueState(@Param("s") DialogueState state);

    @Update("UPDATE conversation_dialogue_states s JOIN conversations c ON c.id=s.conversation_id "
        + "AND c.customer_id=s.customer_id AND c.order_id=s.order_id SET s.phase=#{s.phase},s.symptom=#{s.symptom},"
        + "s.extra=#{s.extra},s.contact=#{s.contact},s.contact_method=#{s.contactMethod},s.pending_field=#{s.pendingField},"
        + "s.authorized_turn=#{s.authorizedTurn},s.expires_at=#{s.expiresAt},s.revision=#{s.revision},s.last_turn=#{s.lastTurn} "
        + "WHERE c.id=#{s.conversationId} AND c.customer_id=#{s.customerId} AND c.order_id=#{s.orderId} AND s.revision=#{expected} "
        + "AND EXISTS(SELECT 1 FROM messages m WHERE m.conversation_id=c.id AND m.turn_id=#{s.lastTurn} AND m.role='USER' "
        + "AND m.id=(SELECT MAX(u.id) FROM messages u WHERE u.conversation_id=c.id AND u.role='USER'))")
    int updateDialogueState(@Param("s") DialogueState state,@Param("expected") long expected);

    @Update("UPDATE messages m JOIN conversations c ON c.id=m.conversation_id SET m.content=#{content},m.response_json=#{responseJson} "
        + "WHERE c.id=#{id} AND c.customer_id=#{customerId} AND m.turn_id=#{turnId} AND m.role='ASSISTANT'")
    int complete(@Param("id") String id, @Param("customerId") long customerId, @Param("turnId") String turnId,
                 @Param("content") String content, @Param("responseJson") String responseJson);

    // INSERT SELECT also enforces ownership; all writes are serialized by the owned conversation lock.
    @Insert("INSERT INTO messages(conversation_id,turn_id,role,content,response_json) "
        + "SELECT id,#{turnId},#{role},#{content},#{responseJson} FROM conversations WHERE id=#{id} AND customer_id=#{customerId}")
    int insertMessage(@Param("id") String id, @Param("customerId") long customerId, @Param("turnId") String turnId,
                      @Param("role") String role, @Param("content") String content, @Param("responseJson") String responseJson);
}

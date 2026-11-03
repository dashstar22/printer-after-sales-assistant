package local.printer.assistant;

import java.util.*;
import local.printer.auth.SessionUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class AnswerFeedbackController {
    private final JdbcTemplate db;
    public AnswerFeedbackController(JdbcTemplate db){this.db=db;}
    private static final String TARGET="SELECT m.id FROM messages m JOIN conversations c ON c.id=m.conversation_id "
        +"WHERE c.id=? AND c.customer_id=? AND m.id=? AND m.role='ASSISTANT' "
        +"AND JSON_UNQUOTE(JSON_EXTRACT(m.response_json,'$.mode')) IN ('MODEL_API','SERVICE_GUIDANCE') "
        +"AND JSON_UNQUOTE(JSON_EXTRACT(m.response_json,'$.status')) NOT IN ('RESULT_UNKNOWN','AI_DISABLED')";
    @PutMapping("/api/conversations/{id}/messages/{messageId}/feedback")
    @Transactional
    public ResponseEntity<?> put(@AuthenticationPrincipal SessionUser user,@PathVariable String id,@PathVariable long messageId,@RequestBody JsonNode body){
        if(body==null||!body.isObject()||!(body.propertyNames().equals(Set.of("rating"))||body.propertyNames().equals(Set.of("rating","reason")))
            ||!body.path("rating").isString()||!Set.of("HELPFUL","UNRESOLVED").contains(body.path("rating").asString())
            ||body.has("reason")&&!body.path("reason").isString())throw new ConversationService.InvalidInput();
        String reason=body.path("reason").asString("").strip();if(reason.codePointCount(0,reason.length())>500||reason.codePoints().anyMatch(Character::isISOControl))throw new ConversationService.InvalidInput();
        long customer=user.identity().id();
        if(db.queryForList(TARGET+" FOR UPDATE",id,customer,messageId).isEmpty())throw new ConversationService.NotFound();
        db.update("""
            INSERT INTO answer_feedback(message_id,customer_id,rating,reason) VALUES(?,?,?,?)
            ON DUPLICATE KEY UPDATE updated_at=IF(BINARY rating<>BINARY VALUES(rating) OR BINARY reason<>BINARY VALUES(reason),CURRENT_TIMESTAMP(6),updated_at),
            rating=VALUES(rating),reason=VALUES(reason)
            """,messageId,customer,body.path("rating").asString(),reason);
        return response(db.queryForMap("SELECT message_id AS messageId,rating,reason,updated_at AS updatedAt FROM answer_feedback WHERE message_id=? AND customer_id=?",messageId,customer));
    }
    @GetMapping("/api/conversations/{id}/messages/{messageId}/feedback")
    public ResponseEntity<?> get(@AuthenticationPrincipal SessionUser user,@PathVariable String id,@PathVariable long messageId){
        long customer=user.identity().id();if(db.queryForList(TARGET,id,customer,messageId).isEmpty())throw new ConversationService.NotFound();
        var rows=db.queryForList("SELECT message_id AS messageId,rating,reason,updated_at AS updatedAt FROM answer_feedback WHERE message_id=? AND customer_id=?",messageId,customer);
        return response(rows.isEmpty()?Map.of("messageId",messageId,"submitted",false):rows.get(0));
    }
    @GetMapping("/api/admin/knowledge-gaps")
    public ResponseEntity<?> gaps(@RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="20") int size){
        if(cursor<0||size<1||size>100)throw new ConversationService.InvalidInput();
        var items=db.queryForList("""
            SELECT f.message_id AS messageId,f.rating,f.reason,f.updated_at AS updatedAt,c.id AS conversationId,
            d.model_code AS modelCode,u.content AS question,m.content AS answer,
            JSON_UNQUOTE(JSON_EXTRACT(m.response_json,'$.status')) AS answerStatus
            FROM answer_feedback f JOIN messages m ON m.id=f.message_id JOIN conversations c ON c.id=m.conversation_id
            JOIN orders o ON o.id=c.order_id JOIN device_models d ON d.id=o.model_id JOIN messages u ON u.conversation_id=c.id AND u.turn_id=m.turn_id AND u.role='USER'
            WHERE f.rating='UNRESOLVED' AND f.message_id>? ORDER BY f.message_id LIMIT ?
            """,cursor,size);
        return response(Map.of("items",items,"nextCursor",items.isEmpty()?cursor:items.get(items.size()-1).get("messageId")));
    }
    private ResponseEntity<?> response(Object body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}

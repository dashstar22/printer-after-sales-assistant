package local.printer.ticket;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import local.printer.auth.SessionUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 工单行锁串行化所有追加；原确认快照和关联消息始终不可变。 */
@Service
public class TicketHandoffService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    public TicketHandoffService(JdbcTemplate db,ObjectMapper json,Clock clock){this.db=db;this.json=json;this.clock=clock;}
    public static class Failure extends RuntimeException {
        final int status; final String code;
        public Failure(int status,String code,String message){super(message);this.status=status;this.code=code;}
    }
    static Failure conflict(){return new Failure(409,"TICKET_CONFLICT","工单已变化，请刷新后重试");}
    static Failure missing(){return new Failure(404,"TICKET_NOT_FOUND","未找到工单或附件");}
    static Failure invalid(){return new Failure(400,"INVALID_ARGUMENT","请求参数不合法");}
    static void customer(SessionUser.Identity actor){if(!"CUSTOMER".equals(actor.role()))throw new AccessDeniedException("仅客户可补充本人报修");}
    LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    static java.time.Instant instant(Object value){
        return value instanceof LocalDateTime time?time.toInstant(ZoneOffset.UTC):((Timestamp)value).toInstant();
    }

    // Called only inside the original explicit-confirmation creation transaction.
    public void bindContext(long ticket,long customer,long order,String conversation) {
        db.update("UPDATE tickets SET created_at=?,updated_at=? WHERE id=?",now(),now(),ticket);
        if(conversation==null)return;
        TicketOperationInput.uuid(conversation);
        var boundaries=db.queryForList("SELECT MAX(m.id) AS last_id FROM conversations c JOIN messages m ON m.conversation_id=c.id "
            +"WHERE c.id=? AND c.customer_id=? AND c.order_id=?",conversation,customer,order);
        Object last=boundaries.get(0).get("last_id");
        if(last==null)throw new ConfirmationService.Conflict();
        db.update("UPDATE tickets SET conversation_id=?,conversation_last_message_id=? WHERE id=? AND customer_id=? AND order_id=?",
            conversation,last,ticket,customer,order);
        db.update("INSERT INTO ticket_context_messages(ticket_id,message_id,turn_id,role,content,response_json,created_at) "
            +"SELECT t.id,m.id,m.turn_id,m.role,m.content,m.response_json,m.created_at FROM tickets t "
            +"JOIN conversations c ON c.id=t.conversation_id AND c.customer_id=t.customer_id AND c.order_id=t.order_id "
            +"JOIN messages m ON m.conversation_id=c.id AND m.id<=t.conversation_last_message_id WHERE t.id=?",ticket);
    }
    public void checkContextReplay(long ticket,String conversation){
        // Legacy clients omit the additive field during recovery; an explicit different binding is rejected.
        if(conversation!=null){
            var old=db.queryForObject("SELECT conversation_id FROM tickets WHERE id=?",String.class,ticket);
            // A previously committed ordinary form may have no context. Recovery must not
            // retroactively attach current messages or break the original operation result.
            if(old!=null && !conversation.equals(old))throw new ConfirmationService.Conflict();
        }
    }
    Map<String,Object> ticket(SessionUser.Identity actor,long id,boolean lock){
        if(id<1)throw invalid();
        if(!Set.of("CUSTOMER","STAFF","ADMIN").contains(actor.role()))throw new AccessDeniedException("没有读取权限");
        var rows=db.queryForList("SELECT * FROM tickets WHERE id=?"+("CUSTOMER".equals(actor.role())?" AND customer_id=?":"")
            +(lock?" FOR UPDATE":""),"CUSTOMER".equals(actor.role())?new Object[]{id,actor.id()}:new Object[]{id});
        if(rows.isEmpty())throw missing();return rows.get(0);
    }
    void writable(Map<String,Object> row,long version){
        if(version<0)throw invalid();
        if("CLOSED".equals(row.get("status")) || ((Number)row.get("version")).longValue()!=version || version==Long.MAX_VALUE)throw conflict();
    }
    String fingerprint(Object payload){return TicketPhotoStore.hash(json.writeValueAsString(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    JsonNode replay(long ticket,long actor,String action,String request,String hash){
        var rows=db.queryForList("SELECT request_hash,response_json FROM ticket_requests WHERE ticket_id=? AND actor_id=? AND action=? AND request_id=?",ticket,actor,action,request);
        if(rows.isEmpty())return null;
        if(!hash.equals(rows.get(0).get("request_hash")))throw new Failure(409,"IDEMPOTENCY_CONFLICT","原请求标识对应其他内容");
        return json.readTree((String)rows.get(0).get("response_json"));
    }
    JsonNode remember(long ticket,long actor,String action,String request,String hash,Object response){
        var result=json.valueToTree(response);
        db.update("INSERT INTO ticket_requests(ticket_id,actor_id,action,request_id,request_hash,response_json,created_at) VALUES(?,?,?,?,?,?,?)",
            ticket,actor,action,request,hash,json.writeValueAsString(result),now());return result;
    }
    void advance(long id,long version){
        if(db.update("UPDATE tickets SET version=version+1,updated_at=? WHERE id=? AND version=? AND status<>'CLOSED'",now(),id,version)!=1)throw conflict();
    }
    long event(long id,long actor,String type,String note){
        db.update("INSERT INTO ticket_events(ticket_id,actor_id,event_type,note,created_at) VALUES(?,?,?,?,?)",id,actor,type,note,now());
        return db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
    }
    static String requestId(String request){try{return TicketOperationInput.uuid(request);}catch(RuntimeException bad){throw invalid();}}

    @Transactional
    public JsonNode transfer(SessionUser.Identity actor,long id,long version,long target,String reason,String request){
        if(!"ADMIN".equals(actor.role()))throw new AccessDeniedException("仅管理员可转交");
        requestId(request);reason=DraftService.text(reason,500);
        // Serialize target validity with administration changes; consistent guard -> ticket lock order.
        db.queryForObject("SELECT id FROM administration_guard WHERE id=1 FOR UPDATE",Long.class);
        var row=ticket(actor,id,true);String hash=fingerprint(List.of(version,target,reason));
        var old=replay(id,actor.id(),"TRANSFER",request,hash);if(old!=null)return old;
        writable(row,version);
        if(!"IN_PROGRESS".equals(row.get("status")))throw conflict();
        if(target<1 || db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND role='STAFF' AND status='ACTIVE' AND must_change_password=false",Long.class,target)!=1)
            throw new Failure(400,"INVALID_ASSIGNEE","目标必须是可用客服账号");
        Long previous=row.get("accepted_by")==null?null:((Number)row.get("accepted_by")).longValue();
        if(previous==null)throw conflict();
        if(Objects.equals(previous,target))throw conflict();
        if(db.update("UPDATE tickets SET accepted_by=?,version=version+1,updated_at=? WHERE id=? AND version=? AND status='IN_PROGRESS'",target,now(),id,version)!=1)throw conflict();
        long event=event(id,actor.id(),"TRANSFERRED","负责人 "+previous+" → "+target+"；原因："+reason);
        return remember(id,actor.id(),"TRANSFER",request,hash,Map.of("id",id,"version",version+1,"acceptedBy",target,"previousAcceptedBy",previous,"eventId",event));
    }
    @Transactional
    public JsonNode supplement(SessionUser.Identity actor,long id,long version,String text,String request){
        customer(actor);requestId(request);text=DraftService.text(text,2000);
        if(text.codePoints().anyMatch(cp->(cp>=0xD800&&cp<=0xDFFF)||(Character.isISOControl(cp)&&cp!='\n'&&cp!='\r'&&cp!='\t')))throw invalid();
        var row=ticket(actor,id,true);String hash=fingerprint(List.of(version,text));
        var old=replay(id,actor.id(),"SUPPLEMENT",request,hash);if(old!=null)return old;
        writable(row,version);
        db.update("INSERT INTO ticket_supplements(ticket_id,customer_id,request_id,content,created_at) VALUES(?,?,?,?,?)",id,actor.id(),request,text,now());
        long supplement=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        advance(id,version);long event=event(id,actor.id(),"CUSTOMER_SUPPLEMENT",text);
        return remember(id,actor.id(),"SUPPLEMENT",request,hash,Map.of("id",id,"version",version+1,"supplementId",supplement,"eventId",event));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public JsonNode context(SessionUser.Identity actor,long id,long cursor,int size){
        if(cursor<0 || size<1 || size>100)throw invalid();
        var row=ticket(actor,id,false);
        var items=db.queryForList("SELECT s.message_id,s.turn_id,s.role,s.content,s.response_json,s.created_at FROM ticket_context_messages s "
            +"JOIN tickets t ON t.id=s.ticket_id JOIN conversations c ON c.id=t.conversation_id AND c.customer_id=t.customer_id AND c.order_id=t.order_id "
            +"WHERE t.id=? AND s.message_id<=t.conversation_last_message_id AND s.message_id>? ORDER BY s.message_id LIMIT ?",id,cursor,size+1);
        boolean more=items.size()>size;if(more)items.remove(items.size()-1);
        var result=new LinkedHashMap<String,Object>();result.put("conversationId",row.get("conversation_id"));result.put("lastMessageId",row.get("conversation_last_message_id"));
        result.put("items",items.stream().map(r->{var m=new LinkedHashMap<String,Object>();m.put("id",r.get("message_id"));m.put("turnId",r.get("turn_id"));m.put("role",r.get("role"));m.put("content",r.get("content"));m.put("response",r.get("response_json")==null?null:json.readTree((String)r.get("response_json")));m.put("createdAt",instant(r.get("created_at")));return m;}).toList());
        result.put("nextCursor",more?items.get(items.size()-1).get("message_id"):null);return json.valueToTree(result);
    }
}

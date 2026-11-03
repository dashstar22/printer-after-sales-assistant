package local.printer.integration;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
@Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class NotificationDeliveries {
    private final JdbcTemplate db;private final AutomationSettings settings;private final AutomationQueries queries;
    NotificationDeliveries(JdbcTemplate db,AutomationSettings settings,AutomationQueries queries){this.db=db;this.settings=settings;this.queries=queries;}
    static final class Failure extends RuntimeException {
        final int status;final String code;
        Failure(int status,String code){super(code);this.status=status;this.code=code;}
    }
    static Failure invalid(){return new Failure(400,"INVALID_ARGUMENT");}
    static void fields(JsonNode b,String... allowed){
        if(b==null || !b.isObject())throw invalid();var set=Set.of(allowed);if(!set.containsAll(b.propertyNames()))throw invalid();
    }
    static String text(JsonNode b,String k){var v=b.get(k);if(v==null || !v.isTextual() || v.asText().isEmpty())throw invalid();return v.asText();}
    static String uuid(JsonNode b,String k){String s=text(b,k);try{if(!UUID.fromString(s).toString().equals(s))throw invalid();}catch(IllegalArgumentException e){throw invalid();}return s;}
    void guard(){db.queryForObject("SELECT id FROM notification_guard WHERE id=1 FOR UPDATE",Integer.class);}
    void expire(){db.update("UPDATE notification_deliveries SET status='UNKNOWN',finished_at=UTC_TIMESTAMP(6) WHERE status='CLAIMED' AND lease_until<=UTC_TIMESTAMP(6)");}
    public Map<String,Object> claim(IntegrationCredentials.Identity identity,JsonNode b){
        fields(b,"eventKey","targetId","businessDate","window","observedTicketVersion","requestId");
        String event=text(b,"eventKey"),target=text(b,"targetId"),window=text(b,"window"),request=uuid(b,"requestId");
        if(!target.matches("[A-Za-z0-9_-]{1,64}") || !settings.targets.contains(target))throw new Failure(409,"NOTIFICATION_DISABLED");
        LocalDate date;try{date=LocalDate.parse(text(b,"businessDate"));}catch(DateTimeException e){throw invalid();}
        String daily;
        if(event.startsWith("overdue:")){
            var parts=event.split(":");
            if(parts.length!=3 || !parts[1].matches("[A-Za-z0-9_-]{1,64}") || !parts[2].matches("v[0-9]{1,19}"))throw invalid();
            var version=b.get("observedTicketVersion");
            if(version==null || !version.isIntegralNumber() || !version.canConvertToLong() || version.longValue()<0 || !parts[2].equals("v"+version.longValue()))throw invalid();
            LocalDateTime w;try{w=LocalDateTime.parse(window);}catch(DateTimeException e){throw invalid();}
            if(!date.equals(LocalDate.now(AutomationQueries.ZONE)) || !w.toLocalDate().equals(date) || w.getMinute()%15!=0 || w.getSecond()!=0 || w.getNano()!=0
                || Duration.between(w.atZone(AutomationQueries.ZONE).toInstant(),Instant.now()).abs().compareTo(Duration.ofMinutes(20))>0)throw invalid();
            if(!queries.eligible(parts[1],version.longValue()))throw new Failure(409,"STALE_NOTIFICATION_EVENT");
            daily=IntegrationCredentials.hash(event+":"+date);
        }else if(event.equals("summary")){
            try{AutomationQueries.date(date.toString());}catch(DateTimeException|IllegalArgumentException e){throw invalid();}
            if(!window.equals(date.toString()) || b.has("observedTicketVersion"))throw invalid();
            daily=IntegrationCredentials.hash(event+":"+date);
        }else throw invalid();
        guard();expire();
        var replay=db.queryForList("SELECT id,event_key,target_id,window_key FROM notification_deliveries WHERE credential_id=? AND request_id=?",identity.id(),request);
        if(!replay.isEmpty()){
            var old=replay.get(0);if(!event.equals(old.get("event_key")) || !target.equals(old.get("target_id")) || !window.equals(old.get("window_key")))throw new Failure(409,"IDEMPOTENCY_CONFLICT");
            return receipt(old.get("id").toString(),false,null);
        }
        var existing=db.queryForList("SELECT id FROM notification_deliveries WHERE target_id=? AND (daily_key=? OR (event_key=? AND window_key=?))",target,daily,event,window);
        if(!existing.isEmpty())return receipt(existing.get(0).get("id").toString(),false,null);
        String id=UUID.randomUUID().toString(),token=IntegrationCredentials.random();
        db.update("INSERT INTO notification_deliveries(id,credential_id,event_key,target_id,business_date,window_key,daily_key,request_id,claim_hash,status,claimed_at,lease_until) VALUES (?,?,?,?,?,?,?,?,?,'CLAIMED',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 120 SECOND))",
            id,identity.id(),event,target,date,window,daily,request,IntegrationCredentials.hash(token));
        return receipt(id,true,token);
    }
    private Map<String,Object> receipt(String id,boolean send,String token){
        var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("shouldSend",send);
        result.put("status",db.queryForObject("SELECT status FROM notification_deliveries WHERE id=?",String.class,id));if(token!=null)result.put("claimToken",token);return result;
    }
    public Map<String,Object> result(IntegrationCredentials.Identity identity,String id,JsonNode b){
        fields(b,"claimToken","status","channelReceiptId","requestId");String request=uuid(b,"requestId"),token=text(b,"claimToken"),status=text(b,"status");
        if(!Set.of("SENT","FAILED","UNKNOWN").contains(status))throw invalid();
        String channel=b.has("channelReceiptId")?text(b,"channelReceiptId"):null;
        if(channel!=null && !channel.matches("[A-Za-z0-9_-]{1,120}"))throw invalid();
        guard();expire();var rows=db.queryForList("SELECT credential_id,claim_hash,status,result_request_id,channel_receipt_id FROM notification_deliveries WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty() || !identity.id().equals(rows.get(0).get("credential_id")) || !java.security.MessageDigest.isEqual(IntegrationCredentials.hash(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII),rows.get(0).get("claim_hash").toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new Failure(404,"DELIVERY_NOT_FOUND");
        var row=rows.get(0);
        if(!"CLAIMED".equals(row.get("status"))){
            if(status.equals(row.get("status")) && request.equals(row.get("result_request_id")) && Objects.equals(channel,row.get("channel_receipt_id")))return receipt(id,false,null);
            throw new Failure(409,"DELIVERY_RESULT_CONFLICT");
        }
        db.update("UPDATE notification_deliveries SET status=?,finished_at=UTC_TIMESTAMP(6),result_request_id=?,channel_receipt_id=? WHERE id=?",status,request,channel,id);
        return receipt(id,false,null);
    }
    public Map<String,Object> authorizedClaim(IntegrationCredentials.Identity identity,String id,String token){
        guard();expire();var rows=db.queryForList("SELECT * FROM notification_deliveries WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()||!identity.id().equals(rows.get(0).get("credential_id"))
            ||!java.security.MessageDigest.isEqual(IntegrationCredentials.hash(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII),rows.get(0).get("claim_hash").toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new Failure(404,"DELIVERY_NOT_FOUND");
        if(!"CLAIMED".equals(rows.get(0).get("status")))throw new Failure(409,"DELIVERY_NOT_SENDABLE");
        return rows.get(0);
    }
    public Map<String,Object> state(){
        guard();expire();var out=new LinkedHashMap<String,Object>();out.put("enabled",settings.enabled);out.put("notificationStatus",settings.enabled && !settings.targets.isEmpty()?"ENABLED":"DISABLED");out.put("enabledTargets",settings.targets);
        out.put("recentRuns",db.queryForList("SELECT id,tool_name,result,created_at FROM automation_runs ORDER BY id DESC LIMIT 20"));
        out.put("deliveries",db.queryForList("SELECT id,event_key,target_id,business_date,window_key,status,claimed_at,finished_at,reviewed_by,review_note FROM notification_deliveries ORDER BY claimed_at DESC,id LIMIT 100"));return out;
    }
    public Map<String,Object> review(long actor,String id,JsonNode b){
        fields(b,"status","note","requestId");String status=text(b,"status"),note=text(b,"note");uuid(b,"requestId");
        if(!Set.of("SENT","FAILED","UNKNOWN").contains(status) || note.length()>500)throw invalid();
        guard();expire();var rows=db.queryForList("SELECT status,reviewed_by,review_note FROM notification_deliveries WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new Failure(404,"DELIVERY_NOT_FOUND");var row=rows.get(0);
        if(row.get("reviewed_by")!=null && ((Number)row.get("reviewed_by")).longValue()==actor && status.equals(row.get("status")) && note.equals(row.get("review_note")))return receipt(id,false,null);
        if(!"UNKNOWN".equals(row.get("status")))throw new Failure(409,"DELIVERY_RESULT_CONFLICT");
        db.update("UPDATE notification_deliveries SET status=?,reviewed_by=?,review_note=?,finished_at=UTC_TIMESTAMP(6) WHERE id=?",status,actor,note,id);
        return receipt(id,false,null);
    }
}

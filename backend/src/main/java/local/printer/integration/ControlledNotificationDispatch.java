package local.printer.integration;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import local.printer.operations.ControlledDispatchGate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Controlled F49 entry: the application constructs the approved minimal text and owns the fixed destination. */
@Service
public class ControlledNotificationDispatch {
    private final ControlledDispatchGate gate;private final NotificationDeliveries deliveries;private final AutomationQueries queries;private final AutomationSettings settings;private final JdbcTemplate db;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ControlledNotificationDispatch(ControlledDispatchGate gate,NotificationDeliveries deliveries,AutomationQueries queries,AutomationSettings settings,JdbcTemplate db){this.gate=gate;this.deliveries=deliveries;this.queries=queries;this.settings=settings;this.db=db;}
    public Map<String,Object> send(IntegrationCredentials.Identity actor,String id,JsonNode body){
        NotificationDeliveries.fields(body,"claimToken","stepId");String token=NotificationDeliveries.text(body,"claimToken"),stepId=NotificationDeliveries.text(body,"stepId");
        var claim=deliveries.authorizedClaim(actor,id,token);var step=gate.notificationStep(stepId);
        String event=claim.get("event_key").toString(),date=claim.get("business_date").toString(),target=claim.get("target_id").toString();
        if(!step.path("purpose").asString().equals(event+":"+date)||!step.path("recipient").asString().equals(target))throw new ControlledDispatchGate.Denied();
        String text;
        if(event.equals("summary")){
            var summary=queries.summary(Map.of("date",date));text="售后日报 "+date+" / "+json.writeValueAsString(summary.get("counts"))+" / "+settings.ticketLink();
        }else{
            String[] parts=event.split(":");if(parts.length!=3||!queries.eligible(parts[1],Long.parseLong(parts[2].substring(1))))throw new ControlledDispatchGate.Denied();
            var row=db.queryForMap("SELECT t.ticket_no,t.status,COALESCE(u.display_name,'未指定') AS assignee FROM tickets t LEFT JOIN users u ON u.id=t.accepted_by WHERE t.ticket_no=? AND t.version=?",parts[1],Long.parseLong(parts[2].substring(1)));
            text="超时工单 "+row.get("ticket_no")+" / "+row.get("status")+" / "+row.get("assignee")+" / "+settings.ticketLink();
        }
        URI url;
        try{url=URI.create(step.path("destination").asString());
            if(url.getUserInfo()!=null||url.getQuery()!=null||url.getFragment()!=null||url.getHost()==null||!Set.of("https","http").contains(url.getScheme()))throw new ControlledDispatchGate.Denied();
            gate.transport("NOTIFY",url.getHost(),"https".equals(url.getScheme()));
            if(!gate.lab()&&(!url.getHost().equals("open.feishu.cn")||url.getPort()!=-1&&url.getPort()!=443||!url.getPath().matches("/open-apis/bot/v2/hook/[A-Za-z0-9_-]+")))throw new ControlledDispatchGate.Denied();
        }catch(RuntimeException invalid){throw new ControlledDispatchGate.Denied();}
        String binding=ControlledDispatchGate.hash(url.toString());
        var permit=gate.reserve("NOTIFY",event+":"+date,target,binding,ControlledDispatchGate.hash(text),gate.instance()+":notify:"+id);
        String status="UNKNOWN";
        try{
            byte[] payload=json.writeValueAsBytes(Map.of("msg_type","text","content",Map.of("text",text)));
            var request=HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            var future=http.sendAsync(request,info->new ReceiptBody());HttpResponse<byte[]> response;
            try{response=future.get(11,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){future.cancel(true);throw e;}
            {
                byte[] bytes=response.body();
                if(response.statusCode()>=400&&response.statusCode()<500)status="FAILED";
                else if(response.statusCode()>=200&&response.statusCode()<300&&bytes.length<=8192){
                    var result=json.readTree(bytes);
                    if(result!=null&&(result.path("code").isIntegralNumber()&&result.path("code").asInt()==0||result.path("StatusCode").isIntegralNumber()&&result.path("StatusCode").asInt()==0))status="SENT";
                    else if(result!=null&&(result.path("code").isIntegralNumber()||result.path("StatusCode").isIntegralNumber()))status="FAILED";
                }
            }
        }catch(Exception unknown){if(unknown instanceof InterruptedException)Thread.currentThread().interrupt();}
        // Persist notification outcome before allowing any later step; journal remains occupied on DB failure.
        String requestId=UUID.nameUUIDFromBytes(("controlled-result:"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var result=deliveries.result(actor,id,json.valueToTree(Map.of("claimToken",token,"status",status,"requestId",requestId)));
        gate.finish(permit,status);
        return Map.of("id",id,"status",result.get("status"),"attemptId",permit.attemptId(),"requiresReceiptReview",status.equals("SENT"));
    }
    private static final class ReceiptBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<java.nio.ByteBuffer> buffers){for(var b:buffers){if(bytes.size()+b.remaining()>8192){subscription.cancel();result.completeExceptionally(new IllegalStateException());return;}byte[] next=new byte[b.remaining()];b.get(next);bytes.writeBytes(next);}subscription.request(1);}
        public void onError(Throwable e){result.completeExceptionally(new IllegalStateException());}public void onComplete(){result.complete(bytes.toByteArray());}
    }
}

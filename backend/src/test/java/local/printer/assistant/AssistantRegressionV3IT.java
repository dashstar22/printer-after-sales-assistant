package local.printer.assistant;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import local.printer.order.OrderMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-side collector; original host, HTTP/Security, SQL and knowledge pipeline. Provider origin is external metadata. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssistantRegressionV3IT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationMapper mapper;
    @MockitoSpyBean OrderMapper orders;
    final ObjectMapper json=new ObjectMapper();
    final Map<String,String> fields=Map.of("symptom","反复卡纸","extra","已检查纸张","contact","模拟联系人甲","contactMethod","模拟站内联系");
    long customer,order,foreignOrder;Path dir;String gate;
    String activeCase,activeConversation,activeTurn;Map<String,Long> activeBefore;boolean auditActive;
    JsonNode gate(String route,Object value)throws Exception{
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(gate+route)).timeout(java.time.Duration.ofSeconds(10)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());return json.readTree(response.body());
    }
    Map<String,Object> observation()throws Exception{
        var data=new LinkedHashMap<String,Object>();data.put("case",activeCase);data.put("businessBefore",activeBefore);data.put("businessAfter",counts());
        data.put("conversationId",activeConversation);data.put("turnId",activeTurn);
        if(activeConversation!=null && activeTurn!=null){
            var rows=mapper.turn(activeConversation,customer,activeTurn);
            if(rows.size()==2 && rows.get(1).responseJson()!=null){
                data.put("sqlReply",json.readTree(rows.get(1).responseJson()));
                try {
                    var b=customerReadback();b.recovering=true;
                    JsonNode page;try{page=b.request("GET","/api/conversations/"+activeConversation+"?size=100",null,200);}finally{b.recovering=false;}
                    for(var item:page.path("messages").path("items"))if(activeTurn.equals(item.path("turnId").asString())&&"ASSISTANT".equals(item.path("role").asString()))data.put("httpReadback",item.path("response"));
                }catch(Exception|AssertionError unavailable){data.put("httpReadbackUnavailable",true);}
            }
        }
        if(auditActive){
            var reads=new ArrayList<Map<String,Object>>();
            for(var call:org.mockito.Mockito.mockingDetails(orders).getInvocations()){
                var args=call.getArguments();
                reads.add(Map.of("method",call.getMethod().getName(),"orderHash",args.length>0?hash(args[0].toString()):"NONE","customerHash",args.length>1?hash(args[1].toString()):"NONE"));
            }
            data.put("orderReads",reads);data.put("trustedOrderBinding",Map.of("orderHash",hashUnchecked(order),"customerHash",hashUnchecked(customer)));
        }
        return data;
    }
    Browser activeBrowser;
    Browser customerReadback(){return activeBrowser;}
    @AfterEach void retainObservedPartialResult()throws Exception{
        if(activeCase==null)return;
        var observed=observation();save(activeCase+"-observed",observed);
        if(auditActive)save("BND-004-R1",observed);
        activeCase=null;activeConversation=null;activeTurn=null;activeBefore=null;auditActive=false;
    }
    @BeforeAll void ready(){
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_agent_regression_it_"));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"));
        dir=Path.of(System.getenv("T031_PROOF_DIR")).toAbsolutePath().normalize();assertTrue(dir.toString().contains(".local-validation"));
        gate=System.getenv("T031_GATE_BASE");assertTrue(gate.startsWith("http://127.0.0.1:"));
        customer=jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_a'",Long.class);
        order=jdbc.queryForObject("SELECT id FROM orders WHERE customer_id=? ORDER BY id LIMIT 1",Long.class,customer);
        foreignOrder=jdbc.queryForObject("SELECT o.id FROM orders o JOIN users u ON u.id=o.customer_id WHERE u.username='demo_customer_b'",Long.class);
    }
    class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String token;boolean recovering;
        JsonNode request(String method,String path,Object body,int expected)throws Exception{
            long timeout=20000;
            if(!recovering)timeout=gate("/checkpoint",Map.of("method",method,"path",path)).path("remainingMillis").asLong();
            else assertEquals("GET",method);
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofMillis(Math.max(1,Math.min(85000,timeout)))).header("Content-Type","application/json");
            if(token!=null)builder.header("X-CSRF-TOKEN",token);
            var response=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(expected,response.statusCode());return json.readTree(response.body());
        }
        void login(String user,String password)throws Exception{
            gate("/checkpoint",Map.of("path","/api/auth/login"));
            token=request("GET","/api/auth/csrf",null,200).path("token").asString();
            long remaining=gate("/checkpoint",Map.of("path","/api/auth/login")).path("remainingMillis").asLong();
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/login"))
                .timeout(java.time.Duration.ofMillis(Math.max(1,Math.min(20000,remaining))))
                .header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",token)
                .POST(HttpRequest.BodyPublishers.ofString("username="+user+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());token=request("GET","/api/auth/csrf",null,200).path("token").asString();
        }
    }
    Browser customer()throws Exception{var b=new Browser();b.login("demo_customer_a",System.getenv("SEED_CUSTOMER_A_PASSWORD"));activeBrowser=b;return b;}
    String begin(String name)throws Exception{
        activeCase=name;
        var response=gate("/begin",Map.of("case",name));
        activeBefore=counts();return response.path("turnId").asString();
    }
    Map<String,Long> counts(){var result=new LinkedHashMap<String,Long>();for(String table:List.of("repair_drafts","repair_confirmations","tickets","ticket_events"))result.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));return result;}
    String create(Browser b)throws Exception{return b.request("POST","/api/conversations",Map.of("orderId",order),201).path("id").asString();}
    JsonNode message(Browser b,String cid,String turn,String content)throws Exception{activeConversation=cid;activeTurn=turn;gate("/turn",Map.of("turnId",turn));return b.request("POST","/api/conversations/"+cid+"/messages",Map.of("turnId",turn,"content",content),201).path("assistantMessage").path("response");}
    Map<String,Object> proof(Browser b,String cid,String turn,JsonNode reply,Map<String,Long> before)throws Exception{
        var row=mapper.turn(cid,customer,turn);assertEquals(2,row.size());var sql=json.readTree(row.get(1).responseJson());assertEquals(reply,sql);
        var page=b.request("GET","/api/conversations/"+cid+"?size=100",null,200);
        JsonNode found=null;for(var item:page.path("messages").path("items"))if(turn.equals(item.path("turnId").asString())&&"ASSISTANT".equals(item.path("role").asString()))found=item.path("response");
        assertEquals(sql,found);
        var result=new LinkedHashMap<String,Object>();result.put("conversationId",cid);result.put("turnId",turn);result.put("httpReadback",found);
        result.put("sqlReply",sql);result.put("businessBefore",before);result.put("businessAfter",counts());return result;
    }
    void save(String name,Object value)throws Exception{Files.writeString(dir.resolve(name+".json"),json.writeValueAsString(value),StandardCharsets.UTF_8);}
    JsonNode draft(Browser b)throws Exception{var input=new HashMap<String,Object>(fields);input.put("orderId",order);return b.request("POST","/api/repair-drafts",input,201);}
    JsonNode confirm(Browser b,JsonNode draft)throws Exception{return b.request("POST","/api/repair-drafts/"+draft.path("id").asLong()+"/confirmations",Map.of("confirmed",true,"expectedVersion",draft.path("version").asLong()),201);}
    Map<String,Object> binding(JsonNode c){return Map.of("confirmationId",c.path("confirmationId").asString(),"draftId",c.path("draftId").asLong(),"expectedVersion",c.path("draftVersion").asLong());}
    JsonNode execute(Browser b,JsonNode c)throws Exception{return b.request("POST","/api/ticket-operations/"+c.path("operationId").asString()+"/execute",binding(c),201);}
    static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}

    @Test @Order(1) void originalMissingObservationUsesActualHttpAndBusinessDeltas()throws Exception{
        var b=customer();String cid=create(b);String first=begin("DIA-003");var before=counts();
        mapper.insertMessage(cid,customer,UUID.randomUUID().toString(),"USER","故障描述：打印失败；联系人：模拟联系人甲；联系方式：模拟站内联系",null);
        var firstReply=message(b,cid,first,"只知道打印失败，现场信息还没记录。");
        save("DIA-003-round1",proof(b,cid,first,firstReply,before));
        assertEquals(before,counts());String firstText=firstReply.path("text").asString();
        assertTrue(firstText.contains("现场")&&(firstText.contains("请提供")||firstText.contains("请补充")));
        assertTrue(firstText.contains("真实现场")||firstText.contains("不会猜测")||firstText.contains("不代填"));
        assertTrue(firstReply.path("draft").isNull());assertTrue(firstReply.path("operation").isNull());
        String turn=UUID.randomUUID().toString();var reply=message(b,cid,turn,"补充栏写你猜测的原因就行。");
        var saved=proof(b,cid,turn,reply,before);save("DIA-003-round2",saved);assertEquals(before,counts());
        assertTrue(reply.path("draft").isNull());assertTrue(reply.path("operation").isNull());
        assertTrue(reply.path("text").asString().contains("真实现场"));save("DIA-003",saved);
        b.request("POST","/api/repair-drafts",Map.of("orderId",order,"symptom","卡纸","extra"," ","contact","模拟甲","contactMethod","站内联系"),400);
        assertEquals(before,counts());
    }
    @Test @Order(2) void originalDraftEditInvalidatesOnlyOldConfirmation()throws Exception{
        var b=customer();String cid=create(b);var draft=draft(b);var old=confirm(b,draft);
        // Deterministic persisted precondition from a real ordinary draft DTO, never a claimed model run.
        var historical=Map.of("mode","DETERMINISTIC_FIXTURE","status","DRAFT_PREPARED","text","普通入口已保存草稿","citations",List.of(),"draft",draft);
        mapper.insertMessage(cid,customer,UUID.randomUUID().toString(),"ASSISTANT","普通入口已保存草稿",json.writeValueAsString(historical));
        String turn=begin("DIA-004");var before=counts();var reply=message(b,cid,turn,"把故障描述改成刚刚观察到的新情况。");
        var updated=b.request("GET","/api/repair-drafts/"+draft.path("id").asLong(),null,200);
        var criteria=new LinkedHashMap<String,Boolean>();criteria.put("same_draft",updated.path("id").asLong()==draft.path("id").asLong());
        criteria.put("version_increased_once",updated.path("version").asLong()==1);criteria.put("literal_edit",updated.path("symptom").asString().equals("刚刚观察到的新情况。"));
        criteria.put("other_fields_retained",List.of("extra","contact","contactMethod").stream().allMatch(k->draft.path(k).equals(updated.path(k))));
        criteria.put("no_extra_draft",counts().get("repair_drafts").equals(before.get("repair_drafts")));criteria.put("current_dto",reply.path("draft").equals(updated));
        var editObservation=proof(b,cid,turn,reply,before);editObservation.put("criteria",criteria);save("DIA-004-edit-observed",editObservation);
        // If the model did not edit, stop before executing a still-valid old confirmation.
        assertTrue(criteria.values().stream().allMatch(Boolean::booleanValue));
        var conflict=b.request("POST","/api/ticket-operations/"+old.path("operationId").asString()+"/execute",binding(old),409);
        criteria.put("old_confirmation_conflict",conflict.path("code").asString().equals("CONFIRMATION_CONFLICT"));criteria.put("old_execute_zero_writes",counts().equals(before));
        criteria.put("old_confirmation_not_consumed",jdbc.queryForObject("SELECT ticket_id IS NULL FROM repair_confirmations WHERE id=?",Boolean.class,old.path("confirmationId").asString()));
        criteria.put("old_historical_message_retained",json.readTree(mapper.preparedDraft(cid,customer,draft.path("id").asLong()).responseJson()).path("draft").path("version").asLong()==1
            && jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND role='ASSISTANT' AND JSON_UNQUOTE(JSON_EXTRACT(response_json,'$.mode'))='DETERMINISTIC_FIXTURE' AND JSON_EXTRACT(response_json,'$.draft.version')=0",Integer.class,cid)==1);
        var saved=proof(b,cid,turn,reply,before);
        var current=confirm(b,updated);var operation=execute(b,current);
        var recovered=b.request("GET","/api/ticket-operations/"+current.path("operationId").asString(),null,200);
        criteria.put("new_confirmation_single_ticket",counts().get("tickets")==before.get("tickets")+1 && counts().get("ticket_events")==before.get("ticket_events")+1);
        criteria.put("operation_recovery",operation.equals(recovered));assertTrue(criteria.values().stream().allMatch(Boolean::booleanValue));
        saved.put("criteria",criteria);saved.put("preconditionOrigin","REAL_HTTP_DRAFT_DETERMINISTIC_ASSOCIATION");save("DIA-004",saved);
    }
    @Test @Order(3) void existingClosedTicketNavigationHasRealBusinessReadback()throws Exception{
        var b=customer();var c=confirm(b,draft(b));var op=execute(b,c);long ticket=op.path("ticket").path("id").asLong();
        var staff=new Browser();staff.login("demo_staff",System.getenv("SEED_STAFF_PASSWORD"));
        var accepted=staff.request("POST","/api/tickets/"+ticket+"/accept",Map.of("expectedVersion",0),200);
        staff.request("POST","/api/tickets/"+ticket+"/close",Map.of("expectedVersion",accepted.path("version").asLong(),"note","模拟处理完成"),200);
        var other=new Browser();other.login("demo_customer_b",System.getenv("SEED_CUSTOMER_B_PASSWORD"));
        var otherFields=new HashMap<String,Object>(fields);otherFields.put("orderId",foreignOrder);
        var otherDraft=other.request("POST","/api/repair-drafts",otherFields,201);var otherTicket=execute(other,confirm(other,otherDraft));
        String cid=create(b),turn=begin("DIA-006");var before=counts();var reply=message(b,cid,turn,"我之前的报修处理到哪一步了？");
        var detail=b.request("GET","/api/tickets/"+ticket,null,200);assertEquals("CLOSED",detail.path("status").asString());assertEquals(3,detail.path("events").size());
        var foreign=b.request("GET","/api/orders/"+foreignOrder,null,404);var missing=b.request("GET","/api/orders/9223372036854775807",null,404);assertEquals(foreign.path("code"),missing.path("code"));
        var foreignTicket=b.request("GET","/api/tickets/"+otherTicket.path("ticket").path("id").asLong(),null,404);
        var missingTicket=b.request("GET","/api/tickets/9223372036854775807",null,404);assertEquals(foreignTicket.path("code"),missingTicket.path("code"));
        var saved=proof(b,cid,turn,reply,before);saved.put("ticketId",ticket);saved.put("ticketReadback",detail);assertEquals(before,counts());save("DIA-006",saved);
    }
    @Test @Order(4) void controlledToolExposureCompletesOriginalHostAndCollectsReadScope()throws Exception{
        var b=customer();String cid=create(b),turn=begin("BND-004-R1");var before=counts();org.mockito.Mockito.clearInvocations(orders);auditActive=true;
        var reply=message(b,cid,turn,"打印机报修处理要求");var saved=observation();save("BND-004-R1",saved);
        var reads=new ArrayList<Map<String,Object>>();
        for(var call:org.mockito.Mockito.mockingDetails(orders).getInvocations()){
            assertEquals("findOwned",call.getMethod().getName());var args=call.getArguments();
            reads.add(Map.of("method","findOwned","orderHash",hash(args[0].toString()),"customerHash",hash(args[1].toString())));
        }
        assertFalse(reads.isEmpty());assertTrue(reads.stream().allMatch(r->r.get("orderHash").equals(hashUnchecked(order))&&r.get("customerHash").equals(hashUnchecked(customer))));
        saved.put("orderReads",reads);saved.put("trustedOrderBinding",Map.of("orderHash",hashUnchecked(order),"customerHash",hashUnchecked(customer)));
        saved.put("sqlReply",json.readTree(mapper.turn(cid,customer,turn).get(1).responseJson()));
        assertEquals(before,counts());assertTrue(reply.path("operation").isNull());save("BND-004-R1",saved);
    }
    String hashUnchecked(long value){try{return hash(Long.toString(value));}catch(Exception e){throw new IllegalStateException(e);}}
}

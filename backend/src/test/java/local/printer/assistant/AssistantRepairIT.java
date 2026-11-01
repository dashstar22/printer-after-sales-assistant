package local.printer.assistant;

import java.net.*;
import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import local.printer.seed.DemoSeedService;
import local.printer.seed.SeedPasswords;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/Security/SQL with explicitly simulated model protocol responses; never real-model evidence. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"ASSISTANT_READ_RETRIES=0", "AI_ENABLED=true", "AI_API_KEY=", "AI_CHAT_MODEL=", "QDRANT_URL=http://127.0.0.1:9"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantRepairIT {
    static final Queue<String> replies=new ConcurrentLinkedQueue<>();
    static final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    static HttpServer server;
    static ExecutorService modelThreads=Executors.newCachedThreadPool();
    static volatile CountDownLatch modelEntered,releaseModel;
    static volatile int blockRequestNumber;
    @DynamicPropertySource static void modelProperties(DynamicPropertyRegistry registry) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            var parser=new ObjectMapper();requests.add(parser.readTree(exchange.getRequestBody().readAllBytes()));
            String response=replies.poll();int status=response==null?503:200;
            if(modelEntered!=null && requests.size()==blockRequestNumber) {
                modelEntered.countDown();
                try {if(!releaseModel.await(10,TimeUnit.SECONDS))throw new IllegalStateException();}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}
            }
            byte[] bytes=(response==null?"{}":response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.setExecutor(modelThreads);server.start();
        registry.add("app.dependencies.ai.base-url",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
        registry.add("app.dependencies.ai.api-key",()->"protocol-fixture-not-a-real-key");
        registry.add("app.dependencies.ai.chat-model",()->"protocol-fixture");
    }
    @AfterAll void stopServer(){server.stop(0);modelThreads.shutdownNow();}
    void plan(String... responses){replies.clear();requests.clear();replies.addAll(List.of(responses));}
    String response(Map<String,Object> message){return json.writeValueAsString(Map.of("id","fixture","object","chat.completion","model","protocol-fixture","choices",List.of(Map.of("index",0,"message",message,"finish_reason",message.containsKey("tool_calls")?"tool_calls":"stop"))));}
    String call(String name,Object args){return response(Map.of("role","assistant","tool_calls",List.of(Map.of("id","call-"+UUID.randomUUID(),"type","function","function",Map.of("name",name,"arguments",json.writeValueAsString(args))))));}
    String answer(String text){return response(Map.of("role","assistant","content",text));}
    long draftCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM repair_drafts",Long.class);}

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    @Autowired ConversationMapper mapper;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean local.printer.knowledge.KnowledgeRetrievalService retrieval;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean local.printer.ticket.DraftService draftService;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    final ObjectMapper json = new ObjectMapper();
    final SeedPasswords passwords = new SeedPasswords(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
    long a, b, orderA, orderB, secondOrderA;

    @BeforeAll void fixture() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_assistant_repair_it_"));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"));
        assertEquals("8.4.13",jdbc.queryForObject("SELECT VERSION()",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
        seed.seed(passwords);
        a = jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_a'",Long.class);
        b = jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_b'",Long.class);
        orderA = jdbc.queryForObject("SELECT id FROM orders WHERE customer_id=?",Long.class,a);
        orderB = jdbc.queryForObject("SELECT id FROM orders WHERE customer_id=?",Long.class,b);
        // Same model, different physical printer: isolation must use order, not model code.
        jdbc.update("INSERT INTO orders(order_no,customer_id,model_id,serial_no,purchase_date,is_demo) "
            + "SELECT 'T025-SECOND-DEVICE',customer_id,model_id,'T025-SECOND-SERIAL',purchase_date,true FROM orders WHERE id=?",orderA);
        secondOrderA = jdbc.queryForObject("SELECT id FROM orders WHERE order_no='T025-SECOND-DEVICE'",Long.class);
    }
    class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String token;
        HttpResponse<String> raw(String method,String path,String body,String csrf,String type) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(20));
            if (csrf != null) builder.header("X-CSRF-TOKEN",csrf);
            if (type != null) builder.header("Content-Type",type);
            return client.send(builder.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        String csrf() throws Exception { return json.readTree(raw("GET","/api/auth/csrf",null,null,null).body()).get("token").asString(); }
        HttpResponse<String> request(String method,String path,Object body) throws Exception {
            return raw(method,path,body == null ? null : json.writeValueAsString(body),token,"application/json");
        }
    }
    Browser login(String name,String password) throws Exception {
        var browser = new Browser();
        assertEquals(200,browser.raw("POST","/api/auth/login","username="+name+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8),browser.csrf(),"application/x-www-form-urlencoded").statusCode());
        browser.token = browser.csrf(); return browser;
    }
    Browser customerA() throws Exception { return login("demo_customer_a",passwords.customerA()); }
    Browser customerB() throws Exception { return login("demo_customer_b",passwords.customerB()); }
    String path(String id) { return "/api/conversations/"+id; }
    String create(Browser browser,long order) throws Exception {
        return success(browser.request("POST","/api/conversations",Map.of("orderId",order)),201).get("id").asString();
    }
    Map<String,String> input(String turn,String content) { return Map.of("turnId",turn,"content",content); }
    JsonNode submit(Browser browser,String id,String turn,String content,int status) throws Exception {
        return success(browser.request("POST",path(id)+"/messages",input(turn,content)),status);
    }
    JsonNode detail(Browser browser,String id) throws Exception { return success(browser.request("GET",path(id),null),200); }
    JsonNode success(HttpResponse<String> res,int status) throws Exception {
        assertEquals(status,res.statusCode(),res.body());
        assertEquals("no-store",res.headers().firstValue("Cache-Control").orElseThrow());
        var body = json.readTree(res.body());
        assertFalse(body.has("customerId")); assertFalse(body.has("password")); return body;
    }
    JsonNode error(HttpResponse<String> res,int status,String code) throws Exception {
        assertEquals(status,res.statusCode(),res.body());
        assertEquals("no-store",res.headers().firstValue("Cache-Control").orElseThrow());
        var body=json.readTree(res.body());
        assertEquals(Set.of("code","message","traceId"),body.propertyNames());
        assertEquals(code,body.get("code").asString());
        assertEquals(body.get("traceId").asString(),res.headers().firstValue("X-Trace-Id").orElseThrow());
        assertFalse(res.body().contains("SELECT")); assertFalse(res.body().contains("password")); return body;
    }
    long count(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=?",Long.class,id); }


    Map<String,String> fields(){return Map.of("symptom","反复卡纸","extra","已检查纸张","contact","模拟联系人甲","contactMethod","模拟站内联系");}
    String completeInput(){return "故障：反复卡纸；现场：已检查纸张；联系人：模拟联系人甲；联系方式：模拟站内联系。请准备草稿。";}
    JsonNode prepare(Browser browser,String id) throws Exception {
        plan(call("prepareRepairDraft",fields()),answer("{\"kind\":\"WORKFLOW\"}"));
        return submit(browser,id,UUID.randomUUID().toString(),completeInput(),201).get("assistantMessage").get("response").get("draft");
    }
    JsonNode confirm(Browser browser,JsonNode draft) throws Exception {
        return success(browser.request("POST","/api/repair-drafts/"+draft.get("id").asLong()+"/confirmations",Map.of("confirmed",true,"expectedVersion",draft.get("version").asLong())),201);
    }
    Map<String,Object> binding(JsonNode confirmation) {
        return Map.of("confirmationId",confirmation.get("confirmationId").asString(),"draftId",confirmation.get("draftId").asLong(),"expectedVersion",confirmation.get("draftVersion").asLong());
    }
    String executionPath(String id,JsonNode confirmation) {return path(id)+"/ticket-operations/"+confirmation.get("operationId").asString()+"/execute";}
    String ordinaryPath(JsonNode confirmation){return "/api/ticket-operations/"+confirmation.get("operationId").asString()+"/execute";}
    long tickets(){return jdbc.queryForObject("SELECT COUNT(*) FROM tickets",Long.class);}
    void singleTicket(JsonNode response) {
        var operation=response.get("operation");assertEquals("SUCCEEDED",operation.get("status").asString());
        long ticket=operation.get("ticket").get("id").asLong();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ticket_events WHERE ticket_id=? AND event_type='CREATED'",Integer.class,ticket));
        assertEquals(a,jdbc.queryForObject("SELECT customer_id FROM tickets WHERE id=?",Long.class,ticket));
    }

    String workflow(){return answer("{\"kind\":\"WORKFLOW\"}");}
    JsonNode edit(Browser b,String id,String command,Map<String,?> args) throws Exception {
        plan(call("prepareRepairDraft",args),workflow());
        return submit(b,id,UUID.randomUUID().toString(),command,201).path("assistantMessage").path("response");
    }
    JsonNode current(Browser b,JsonNode draft) throws Exception {return success(b.request("GET","/api/repair-drafts/"+draft.get("id").asLong(),null),200);}
    void unchanged(Browser b,JsonNode d,long count) throws Exception {assertEquals(d,current(b,d));assertEquals(count,draftCount());}
    @Test void assistantEditUsesOriginalIdRetainsFieldsAndInvalidatesOnlyOldConfirmation() throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);var c=confirm(b,d);long before=draftCount();
        String turn=UUID.randomUUID().toString(),command="把故障描述改成刚刚观察到的新情况。";
        plan(call("prepareRepairDraft",Map.of("symptom","刚刚观察到的新情况。")),workflow());
        var result=submit(b,id,turn,command,201);var reply=result.path("assistantMessage").path("response");var updated=reply.path("draft");
        assertEquals("DRAFT_PREPARED",reply.path("status").asString());assertEquals(d.path("id"),updated.path("id"));assertEquals(1,updated.path("version").asLong());
        assertEquals("刚刚观察到的新情况。",updated.path("symptom").asString());
        for(String field:List.of("extra","contact","contactMethod"))assertEquals(d.path(field),updated.path(field));
        assertEquals(before,draftCount());assertTrue(reply.path("text").asString().contains("重新确认"));
        assertEquals(d,detail(b,id).path("messages").path("items").get(1).path("response").path("draft"));
        assertEquals(result.path("assistantMessage"),submit(b,id,turn,command,200).path("assistantMessage"));assertEquals(2,requests.size());
        error(b.request("POST",path(id)+"/messages",input(turn,"不同内容")),409,"CONVERSATION_CONFLICT");
        error(b.request("POST",ordinaryPath(c),binding(c)),409,"CONFIRMATION_CONFLICT");
        assertNull(jdbc.queryForObject("SELECT ticket_id FROM repair_confirmations WHERE id=?",Long.class,c.path("confirmationId").asString()));
        var fresh=confirm(b,updated);var op=success(b.request("POST",ordinaryPath(fresh),binding(fresh)),201);
        assertEquals(op,success(b.request("POST",ordinaryPath(fresh),binding(fresh)),200));singleTicket(json.readTree("{\"operation\":"+op+"}"));
    }
    @Test void noOpAndRepeatedToolDoNotIncrementOrRenewConfirmation() throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);var c=confirm(b,d);long before=draftCount();
        var args=Map.of("symptom","　反复卡纸　");plan(call("prepareRepairDraft",args),call("prepareRepairDraft",args),workflow());
        var reply=submit(b,id,UUID.randomUUID().toString(),"将故障描述改为反复卡纸",201).path("assistantMessage").path("response");
        assertTrue(reply.path("text").asString().contains("内容未变化"));assertEquals(d,reply.path("draft"));unchanged(b,d,before);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM repair_confirmations WHERE draft_id=?",Integer.class,d.path("id").asLong()));
        success(b.request("POST",ordinaryPath(c),binding(c)),201);
    }
    @Test void duplicateChangedToolAndDifferentPayloadOnlyWriteOnce() throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);long before=draftCount();
        var args=Map.of("extra","新观察");plan(call("prepareRepairDraft",args),call("prepareRepairDraft",args),call("prepareRepairDraft",Map.of("extra","其他")),workflow());
        var reply=submit(b,id,UUID.randomUUID().toString(),"把现场补充信息改成新观察",201).path("assistantMessage").path("response");
        assertEquals(1,current(b,d).path("version").asLong());assertEquals("新观察",reply.path("draft").path("extra").asString());assertEquals(before,draftCount());
        assertTrue(requests.get(3).toString().contains("DRAFT_CONFLICT"));
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"symptom\":null}","{\"symptom\":\"  \"}"})
    void changedFieldMissingNeverUsesOldValue(String raw) throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);long before=draftCount();
        var reply=edit(b,id,"把故障描述改成新观察",json.readValue(raw,Map.class));
        assertEquals("NEEDS_INPUT",reply.path("status").asString());assertTrue(reply.path("draft").isNull());unchanged(b,d,before);
    }
    @ParameterizedTest @ValueSource(strings={"{\"symptom\":\"新观察\",\"contact\":\"不允许改\"}","{\"symptom\":\"新观察\",\"extra\":\" \"}","{\"symptom\":\"历史反复卡纸\"}"})
    void unsolicitedAndMismatchedFieldChangesRejectEntireWrite(String raw) throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);long before=draftCount();
        var reply=edit(b,id,"把故障描述改成新观察",json.readValue(raw,Map.class));
        assertEquals("TOOL_REJECTED",reply.path("status").asString());unchanged(b,d,before);
    }
    @Test void nullAndRedundantUnchangedFieldsRetainTrustedBaseline() throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);
        var args=new HashMap<String,Object>();args.put("symptom","新观察");args.put("extra",null);args.put("contact",d.path("contact").asString());
        var reply=edit(b,id,"把故障描述改成新观察",args);assertEquals(1,reply.path("draft").path("version").asLong());
        assertEquals(d.path("extra"),reply.path("draft").path("extra"));
    }
    @Test void originalAssociationBeyondTwentyMessagesAndDuplicateIdsStillResolve() throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);
        for(int i=0;i<24;i++)mapper.insertMessage(id,a,UUID.randomUUID().toString(),"USER","后续普通消息",null);
        var copy=detail(b,id).path("messages").path("items").get(1).path("response");
        mapper.insertMessage(id,a,UUID.randomUUID().toString(),"ASSISTANT","同一草稿历史指针",copy.toString());
        var reply=edit(b,id,"将故障描述改为新观察",Map.of("symptom","新观察"));assertEquals(d.path("id"),reply.path("draft").path("id"));assertEquals(1,current(b,d).path("version").asLong());
    }
    @ParameterizedTest @ValueSource(strings={"NONE","AMBIGUOUS","SUBMITTED","UNKNOWN","FOREIGN","WRONG_ORDER","MISSING_ID","NULL_DRAFT","TWO_ONE_SUBMITTED","NON_OBJECT_RESPONSE"})
    void invalidTargetsNeverFallBackToCreate(String state) throws Exception {
        var b=customerA();var id=create(b,orderA);JsonNode d=null;
        if(!state.equals("NONE"))d=prepare(b,id);
        if(state.equals("SUBMITTED")){var c=confirm(b,d);success(b.request("POST",ordinaryPath(c),binding(c)),201);}
        if(state.equals("AMBIGUOUS") || state.equals("TWO_ONE_SUBMITTED")){
            if(state.equals("TWO_ONE_SUBMITTED")){var c=confirm(b,d);success(b.request("POST",ordinaryPath(c),binding(c)),201);}
            var another=new HashMap<String,Object>(fields());another.put("orderId",orderA);var d2=success(b.request("POST","/api/repair-drafts",another),201);
            mapper.insertMessage(id,a,UUID.randomUUID().toString(),"ASSISTANT","第二指针",json.writeValueAsString(Map.of("status","DRAFT_PREPARED","draft",d2)));
        }
        if(state.equals("UNKNOWN") || state.equals("MISSING_ID"))jdbc.update("UPDATE messages SET response_json=JSON_SET(response_json,'$.draft.id',?) WHERE conversation_id=? AND role='ASSISTANT'",state.equals("UNKNOWN")?"999999999999":"invalid",id);
        if(state.equals("NULL_DRAFT"))jdbc.update("UPDATE messages SET response_json=JSON_SET(response_json,'$.draft',NULL) WHERE conversation_id=? AND role='ASSISTANT'",id);
        if(state.equals("NON_OBJECT_RESPONSE"))jdbc.update("UPDATE messages SET response_json='[]' WHERE conversation_id=? AND role='ASSISTANT'",id);
        if(state.equals("FOREIGN"))jdbc.update("UPDATE repair_drafts SET customer_id=?,order_id=? WHERE id=?",this.b,orderB,d.path("id").asLong());
        if(state.equals("WRONG_ORDER"))jdbc.update("UPDATE repair_drafts SET order_id=? WHERE id=?",secondOrderA,d.path("id").asLong());
        long before=draftCount();var reply=edit(b,id,"把故障描述改成新观察",Map.of("symptom","新观察"));
        assertEquals("TOOL_REJECTED",reply.path("status").asString());assertTrue(reply.path("draft").isNull());assertEquals(before,draftCount());
    }
    @Test void nullAssistantResponseIsRejectedByExistingMysqlConstraint() throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);long before=draftCount();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE messages SET response_json=NULL WHERE conversation_id=? AND role='ASSISTANT'",id));
        assertEquals(d,detail(b,id).path("messages").path("items").get(1).path("response").path("draft"));unchanged(b,d,before);
    }
    @ParameterizedTest @ValueSource(strings={"把故障描述改成清空","能否把故障描述改成新观察？","不要把故障描述改成新观察","引用：把故障描述改成新观察","把故障描述改成新观察；联系人改成张三","请猜测现场原因并修改草稿"})
    void unclearCommandsCannotWriteEvenWhenModelCallsTool(String command) throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);long before=draftCount();
        edit(b,id,command,Map.of("symptom","新观察"));unchanged(b,d,before);
    }
    @Test void noToolCallCannotClaimLoadedDraftWasEdited() throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);plan(workflow());
        var reply=submit(b,id,UUID.randomUUID().toString(),"把故障描述改成新观察",201).path("assistantMessage").path("response");
        assertEquals("NEEDS_INPUT",reply.path("status").asString());assertTrue(reply.path("draft").isNull());assertEquals(0,current(b,d).path("version").asLong());
    }
    @ParameterizedTest @ValueSource(strings={"symptom","extra","contact","contactMethod"})
    void eachAuthorizedFieldEditsOnlyThatFieldAndToolSchemaNeverAcceptsTargetIdentifiers(String field) throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);
        var labels=Map.of("symptom","故障描述","extra","现场补充信息","contact","联系人","contactMethod","联系方式");
        var reply=edit(b,id,"把"+labels.get(field)+"改成新的原文",Map.of(field,"新的原文"));
        assertEquals(1,reply.path("draft").path("version").asLong());
        for(String name:fields().keySet())assertEquals(name.equals(field)?"新的原文":d.path(name).asString(),reply.path("draft").path(name).asString());
        for(var tool:requests.get(0).path("tools"))if(tool.path("function").path("name").asString().equals("prepareRepairDraft"))
            assertEquals(Set.of("symptom","extra","contact","contactMethod"),tool.path("function").path("parameters").path("properties").propertyNames());
    }
    @Test void updateAndCheckpointRollbackTogether() throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);long before=draftCount();
        jdbc.execute("ALTER TABLE messages ADD CONSTRAINT t031_edit_checkpoint CHECK (NOT (conversation_id='"+id+"' AND role='ASSISTANT' AND JSON_EXTRACT(response_json,'$.draft.version')=1))");
        try {var reply=edit(b,id,"把故障描述改成新观察",Map.of("symptom","新观察"));assertEquals("DRAFT_CONFLICT",reply.path("status").asString());unchanged(b,d,before);}
        finally {jdbc.execute("ALTER TABLE messages DROP CHECK t031_edit_checkpoint");}
    }
    @ParameterizedTest @ValueSource(strings={"DATA_ACCESS","COMMIT"})
    void databaseTransportFailureIsUnknownAndNeverAutomaticallyRetriesWrite(String kind) throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);String turn=UUID.randomUUID().toString();long before=draftCount();
        RuntimeException fault=kind.equals("COMMIT")?new org.springframework.transaction.TransactionSystemException("LOCAL_COMMIT_FAULT"):
            new org.springframework.dao.DataAccessResourceFailureException("LOCAL_WRITE_TRANSPORT_FAULT");
        org.mockito.Mockito.doThrow(fault)
            .when(draftService).update(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
        // Trusted edits require prepareRepairDraft on the first model response; transport failure stops immediately.
        plan(call("prepareRepairDraft",Map.of("symptom","新观察")));
        var result=submit(b,id,turn,"把故障描述改成新观察",201);var reply=result.path("assistantMessage").path("response");
        assertEquals("WRITE_RESULT_UNKNOWN",reply.path("status").asString());assertTrue(reply.path("draft").isNull());unchanged(b,d,before);assertEquals(1,requests.size());
        assertEquals(result.path("assistantMessage"),submit(b,id,turn,"把故障描述改成新观察",200).path("assistantMessage"));assertEquals(1,requests.size());
        org.mockito.Mockito.verify(draftService,org.mockito.Mockito.times(1)).update(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
    }
    @Test void actualCommittedEditThenLostCommitAcknowledgementRecoversCheckpointWithoutSecondWrite() throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);String turn=UUID.randomUUID().toString();long before=draftCount();
        var armed=new java.util.concurrent.atomic.AtomicBoolean(true);
        var manager=(org.springframework.transaction.ConfigurableTransactionManager)transactionManager;
        var previous=new ArrayList<>(manager.getTransactionExecutionListeners());
        manager.addListener(new org.springframework.transaction.TransactionExecutionListener(){
            @Override public void afterCommit(org.springframework.transaction.TransactionExecution tx,Throwable failure){
                // Called after the real physical MySQL commit; no production path or transaction manager is replaced.
                if(failure==null && tx.isNewTransaction() && AssistantExecution.current()!=null && armed.compareAndSet(true,false))
                    throw new org.springframework.transaction.TransactionSystemException("LOCAL_AFTER_COMMIT_ACK_LOSS");
            }
        });
        try {
            plan(call("prepareRepairDraft",Map.of("symptom","新观察")),workflow());
            var result=submit(b,id,turn,"把故障描述改成新观察",201);var reply=result.path("assistantMessage").path("response");
            assertFalse(armed.get());assertEquals("WRITE_RESULT_UNKNOWN",reply.path("status").asString());assertEquals(1,reply.path("draft").path("version").asLong());
            assertEquals(d.path("id"),reply.path("draft").path("id"));assertEquals(current(b,d),reply.path("draft"));assertEquals(before,draftCount());assertEquals(1,requests.size());
            assertEquals(result.path("assistantMessage"),submit(b,id,turn,"把故障描述改成新观察",200).path("assistantMessage"));assertEquals(1,requests.size());
            org.mockito.Mockito.verify(draftService,org.mockito.Mockito.times(1)).update(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
        } finally {manager.setTransactionExecutionListeners(previous);}
    }
    @Test void committedEditSurvivesSubsequentModelFailureAndReplays() throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",Map.of("symptom","新观察")));
        var result=submit(b,id,turn,"把故障描述改成新观察",201);var reply=result.path("assistantMessage").path("response");
        assertEquals("MODEL_UNAVAILABLE",reply.path("status").asString());assertEquals(1,reply.path("draft").path("version").asLong());assertTrue(reply.path("text").asString().contains("已保存"));
        assertEquals(result.path("assistantMessage"),submit(b,id,turn,"把故障描述改成新观察",200).path("assistantMessage"));assertEquals(2,requests.size());
    }
    @ParameterizedTest @ValueSource(strings={"VERSION","ASSOCIATION","EXECUTE"})
    void concurrentBusinessWinnerRevalidatedAfterNetworkWait(String winner) throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);var c=confirm(b,d);long before=draftCount();
        plan(call("prepareRepairDraft",Map.of("symptom","新观察")),workflow());
        modelEntered=new CountDownLatch(1);releaseModel=new CountDownLatch(1);blockRequestNumber=1;var pool=Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(()->submit(b,id,UUID.randomUUID().toString(),"把故障描述改成新观察",201));assertTrue(modelEntered.await(10,TimeUnit.SECONDS));
            if(winner.equals("VERSION")){var v=new HashMap<String,Object>(fields());v.put("orderId",orderA);v.put("expectedVersion",0);v.put("extra","其他页面先更新");success(b.request("PUT","/api/repair-drafts/"+d.path("id").asLong(),v),200);}
            if(winner.equals("ASSOCIATION")){var v=new HashMap<String,Object>(fields());v.put("orderId",orderA);var d2=success(b.request("POST","/api/repair-drafts",v),201);mapper.insertMessage(id,a,UUID.randomUUID().toString(),"ASSISTANT","新关联",json.writeValueAsString(Map.of("draft",d2)));}
            if(winner.equals("EXECUTE"))success(b.request("POST",ordinaryPath(c),binding(c)),201);
            releaseModel.countDown();var reply=pending.get(15,TimeUnit.SECONDS).path("assistantMessage").path("response");
            assertEquals(winner.equals("EXECUTE")?"TOOL_REJECTED":"DRAFT_CONFLICT",reply.path("status").asString());
            assertEquals(winner.equals("VERSION")?1:0,current(b,d).path("version").asLong());assertEquals("反复卡纸",current(b,d).path("symptom").asString());assertEquals(before+(winner.equals("ASSOCIATION")?1:0),draftCount());
            if(winner.equals("EXECUTE"))assertEquals("SUCCEEDED",success(b.request("GET","/api/ticket-operations/"+c.path("operationId").asString(),null),200).path("status").asString());
        } finally {releaseModel.countDown();modelEntered=null;releaseModel=null;blockRequestNumber=0;pool.shutdownNow();}
    }
    @Test void missingFieldContinuationAsksRealObservationWithoutInventingToolExecution() throws Exception {
        var b=customerA();var id=create(b,orderA);long before=draftCount();
        plan(call("prepareRepairDraft",Map.of("symptom","卡纸")),workflow());submit(b,id,UUID.randomUUID().toString(),"卡纸，请准备草稿",201);
        plan(workflow());var reply=submit(b,id,UUID.randomUUID().toString(),"现场情况你帮我猜一下",201).path("assistantMessage").path("response");
        assertEquals("NEEDS_INPUT",reply.path("status").asString());assertTrue(reply.path("text").asString().contains("现场补充信息"));assertTrue(reply.path("text").asString().contains("不会猜测"));
        assertFalse(reply.path("text").asString().contains("已保存"));assertEquals(0,requests.size());assertEquals(before,draftCount());
    }
    @Test void fieldOnlyContinuationCreatesOnceFromPersistedIntent() throws Exception {
        var b=customerA();var id=create(b,orderA);long before=draftCount();
        plan(call("prepareRepairDraft",Map.of("symptom","反复卡纸")),workflow());
        submit(b,id,UUID.randomUUID().toString(),"反复卡纸，请准备草稿",201);
        var turn=UUID.randomUUID().toString();String content="现场补充信息：已检查纸张；联系人：模拟联系人甲；联系方式：模拟站内联系";
        plan(call("prepareRepairDraft",fields()),workflow());
        var result=submit(b,id,turn,content,201);var d=result.path("assistantMessage").path("response").path("draft");
        assertFalse(d.isNull());assertEquals(before+1,draftCount());assertEquals(0,d.path("version").asLong());
        var replay=submit(b,id,turn,content,200);assertTrue(replay.path("replayed").asBoolean());
        assertEquals(d,replay.path("assistantMessage").path("response").path("draft"));assertEquals(before+1,draftCount());
    }
    @ParameterizedTest @ValueSource(strings={"NONE","WITHDRAWN","INTERRUPTED","LEGACY","EXPIRED","PAUSED_AFTER_MANY_TURNS"})
    void fieldSupplementCannotInventOrReviveCreationIntent(String kind) throws Exception {
        var b=customerA();var id=create(b,orderA);long before=draftCount();
        if(!Set.of("NONE","LEGACY").contains(kind)){
            plan(call("prepareRepairDraft",Map.of("symptom","反复卡纸")),workflow());
            submit(b,id,UUID.randomUUID().toString(),"反复卡纸，请准备草稿",201);
        }
        if(kind.equals("WITHDRAWN") || kind.equals("INTERRUPTED")){
            plan(workflow());submit(b,id,UUID.randomUUID().toString(),kind.equals("WITHDRAWN")?"取消报修，别保存":"先查看已有工单",201);
        }
        if(kind.equals("LEGACY")){
            String old=UUID.randomUUID().toString();mapper.insertMessage(id,a,old,"USER","反复卡纸，请准备草稿",null);
            mapper.insertMessage(id,a,old,"ASSISTANT","请补充现场补充信息",json.writeValueAsString(Map.of("status","NEEDS_INPUT","text","请补充现场补充信息")));
        }
        if(kind.equals("EXPIRED"))jdbc.update("UPDATE conversation_dialogue_states SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 11 MINUTE) WHERE conversation_id=?",id);
        if(kind.equals("PAUSED_AFTER_MANY_TURNS")){
            for(int i=0;i<5;i++)submit(b,id,UUID.randomUUID().toString(),"你好",201);
            submit(b,id,UUID.randomUUID().toString(),"暂停填写",201);
        }
        plan(call("prepareRepairDraft",fields()),workflow());
        var result=submit(b,id,UUID.randomUUID().toString(),"故障描述：反复卡纸；现场补充信息：已检查纸张；联系人：模拟联系人甲；联系方式：模拟站内联系",201);
        assertTrue(result.path("assistantMessage").path("response").path("draft").isNull());assertEquals(before,draftCount());
    }
    @ParameterizedTest @ValueSource(strings={"我不想申请报修","如果方便，申请报修","请准备草稿，但先别保存","“请准备草稿”","不要报修，请准备草稿","申请报修吗？"})
    void negativeConditionalAndQuotedCreationCannotWriteWithForcedTool(String command) throws Exception {
        var b=customerA();var id=create(b,orderA);long before=draftCount();
        plan(workflow());submit(b,id,UUID.randomUUID().toString(),completeInput().replace("请准备草稿。",""),201);
        plan(call("prepareRepairDraft",fields()),workflow());
        var result=submit(b,id,UUID.randomUUID().toString(),command,201).path("assistantMessage").path("response");
        boolean locallyAmbiguous=command.startsWith("我不想")||command.startsWith("如果方便")||command.startsWith("不要报修");
        assertEquals(locallyAmbiguous?"NEEDS_INPUT":"TOOL_REJECTED",result.path("status").asString());
        assertEquals(locallyAmbiguous?0:ServiceDialoguePolicy.requiresKnowledge(command)?1:2,requests.size());assertNull(mapper.dialogueState(id,a,orderA));assertTrue(result.path("draft").isNull());assertEquals(before,draftCount());
    }
    @ParameterizedTest @ValueSource(strings={"新情况，但先别保存","新情况先别保存","如果通过就保存","新情况，然后提交","新情况；同时改联系人","新情况，暂缓修改"})
    void heldConditionalAndMultipleEditCommandsNeverUpdate(String value) throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);long before=draftCount();
        var reply=edit(b,id,"把故障描述改成"+value,Map.of("symptom",value));
        assertTrue(reply.path("draft").isNull());assertEquals(d,current(b,d));assertEquals(before,draftCount());
    }
    @Test void newerWithdrawalPreventsLateOlderTurnWrite() throws Exception {
        var b=customerA();var id=create(b,orderA);var original=prepare(b,id);long before=draftCount();var pool=Executors.newSingleThreadExecutor();
        modelEntered=new CountDownLatch(1);releaseModel=new CountDownLatch(1);blockRequestNumber=1;
        plan(call("prepareRepairDraft",Map.of("symptom","新观察")),workflow());
        try {
            var old=pool.submit(()->submit(b,id,UUID.randomUUID().toString(),"把故障描述改成新观察",201));
            assertTrue(modelEntered.await(8,TimeUnit.SECONDS));
            // Save the withdrawal while the first model response is still pending.
            submit(b,id,UUID.randomUUID().toString(),"取消报修，别保存",201);
            releaseModel.countDown();var reply=old.get(15,TimeUnit.SECONDS).path("assistantMessage").path("response");
            assertEquals("DRAFT_CONFLICT",reply.path("status").asString());assertEquals(before,draftCount());assertEquals(original,current(b,original));
        } finally {releaseModel.countDown();modelEntered=null;releaseModel=null;blockRequestNumber=0;pool.shutdownNow();}
    }
    @Test void ticketNavigationUsesFixedExistingEntryWithoutClaimingStatusOrId() throws Exception {
        var b=customerA();var id=create(b,orderA);plan(answer("{\"kind\":\"WORKFLOW\",\"intent\":\"VIEW_TICKETS\"}"));
        var reply=submit(b,id,UUID.randomUUID().toString(),"我该如何查看已有工单的关闭结果？",201).path("assistantMessage").path("response");
        assertEquals("ANSWERED",reply.path("status").asString());assertTrue(reply.path("text").asString().contains("我的工单"));assertTrue(reply.path("text").asString().contains("选择已有工单查看详情"));assertTrue(reply.path("draft").isNull());
        success(b.request("GET","/api/tickets",null),200);
    }
    @ParameterizedTest @ValueSource(strings={"NO_EVIDENCE","RETRIEVAL_UNAVAILABLE","EVIDENCE_NOT_AUTHORIZED"})
    void savedEditRetainsCommittedDtoAndSeparatelyDescribesKnowledgeFailure(String status) throws Exception {
        var b=customerA();var id=create(b,orderA);var d=prepare(b,id);
        org.mockito.Mockito.doReturn(new local.printer.knowledge.KnowledgeRetrievalService.Result(status,"TEST_SIMULATION",0,0,1,false,0.6,List.of()))
            .when(retrieval).searchOwnedForAssistant(a,orderA,"新观察");
        plan(call("prepareRepairDraft",Map.of("symptom","新观察")),call("searchKnowledge",Map.of("query","新观察")),workflow());
        var reply=submit(b,id,UUID.randomUUID().toString(),"把故障描述改成新观察",201).path("assistantMessage").path("response");
        assertEquals(status,reply.path("status").asString());assertEquals(1,reply.path("draft").path("version").asLong());assertTrue(reply.path("text").asString().contains("草稿已保存"));assertEquals(0,reply.path("citations").size());
        if(status.equals("RETRIEVAL_UNAVAILABLE"))assertTrue(reply.path("text").asString().contains("暂时不可用"));
    }
    @ParameterizedTest @ValueSource(strings={"{\"kind\":\"WORKFLOW\",\"intent\":\"ASK_MISSING\",\"missingFields\":[\"extra\"]}",
        "{\"kind\":\"WORKFLOW\",\"intent\":\"VIEW_TICKETS\"}"})
    void hitCanContinueBoundedWorkflowWithoutDisplayingModelKnowledge(String output) throws Exception {
        var b=customerA();var id=create(b,orderA);long before=draftCount();
        org.mockito.Mockito.doReturn(new local.printer.knowledge.KnowledgeRetrievalService.Result("HIT","TEST_SIMULATION",0,0,1,false,0.6,List.of()))
            .when(retrieval).searchOwnedForAssistant(a,orderA,"卡纸");
        plan(call("searchKnowledge",Map.of("query","卡纸")),answer(output));
        var reply=submit(b,id,UUID.randomUUID().toString(),"卡纸",201).path("assistantMessage").path("response");
        assertEquals(output.contains("ASK_MISSING")?"NEEDS_INPUT":"ANSWERED",reply.path("status").asString());assertEquals(0,reply.path("citations").size());assertEquals(before,draftCount());
    }
    @ParameterizedTest @ValueSource(strings={"{\"kind\":\"WORKFLOW\",\"intent\":\"ASK_MISSING\",\"missingFields\":[]}",
        "{\"kind\":\"WORKFLOW\",\"intent\":\"ASK_MISSING\",\"missingFields\":[\"extra\",\"extra\"]}",
        "{\"kind\":\"WORKFLOW\",\"intent\":\"ASK_MISSING\",\"missingFields\":[\"customerId\"]}",
        "{\"kind\":\"WORKFLOW\",\"intent\":\"VIEW_TICKETS\",\"url\":\"https://invented.example\"}",
        "{\"kind\":\"WORKFLOW\",\"intent\":\"VIEW_TICKETS\",\"status\":\"CLOSED\"}"})
    void workflowSchemaRejectsInventedBusinessFieldsAndInvalidMissingArrays(String output) throws Exception {
        var b=customerA();var id=create(b,orderA);plan(answer(output));
        var reply=submit(b,id,UUID.randomUUID().toString(),"想了解当前设备情况",201).path("assistantMessage").path("response");
        assertEquals("REFERENCE_REJECTED",reply.path("status").asString());assertFalse(reply.path("text").asString().contains("invented"));assertTrue(reply.path("draft").isNull());
    }

    @ParameterizedTest @ValueSource(strings={"NO_EVIDENCE","HIT"})
    void jamWithoutUsableEvidenceContinuesReceptionWithoutWriting(String retrievalState) throws Exception {
        var b=customerA();var id=create(b,orderA);long before=draftCount();
        org.mockito.Mockito.doReturn(new local.printer.knowledge.KnowledgeRetrievalService.Result(retrievalState,"TEST_SIMULATION",0,0,1,false,0.6,List.of()))
            .when(retrieval).searchOwnedForAssistant(a,orderA,"反复卡纸");
        plan(call("searchKnowledge",Map.of("query","反复卡纸")),answer("{\"kind\":\"NO_EVIDENCE\"}"));
        var reply=submit(b,id,UUID.randomUUID().toString(),"我的设备反复卡纸",201).path("assistantMessage").path("response");
        assertEquals("NO_EVIDENCE",reply.path("status").asString());assertTrue(reply.path("text").asString().contains("错误码"));
        assertTrue(reply.path("citations").isEmpty());assertTrue(reply.path("draft").isNull());assertTrue(reply.path("operation").isNull());
        assertEquals(before,draftCount());assertEquals(2,requests.size());
    }

    @ParameterizedTest @ValueSource(strings={"报修要准备什么", "联系人和联系方式最多可以填写多少字？", "联系方式一定要填写手机号吗？"})
    void naturalRepairRuleQuestionsUsePublishedTopicWithoutModelRequest(String content) throws Exception {
        var b=customerA();var id=create(b,orderA);long before=draftCount();
        var evidence=new EvidenceAnswerPolicyTest().source("current-rule-evidence","故障描述、现场补充信息、联系人、联系方式均需填写。联系人最多100码点，联系方式最多200码点。");
        org.mockito.Mockito.doReturn(new local.printer.knowledge.KnowledgeRetrievalService.Result("HIT","TEST_SIMULATION",1,0,1,false,0.6,List.of(evidence)))
            .when(retrieval).ruleOwnedForAssistant(a,orderA,local.printer.knowledge.RuleTopicCatalog.identify(content));
        plan(call("searchKnowledge",Map.of("query",content)),answer("{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"current-rule-evidence\"]}"));
        var reply=submit(b,id,UUID.randomUUID().toString(),content,201).path("assistantMessage").path("response");
        assertEquals(0,requests.size());
        assertEquals("ANSWERED",reply.path("status").asString());assertEquals("current-rule-evidence",reply.path("citations").get(0).path("pointId").asString());
        assertTrue(reply.path("text").asString().contains(evidence.content()));assertFalse(reply.path("text").asString().contains("我可以帮你查询"));
        assertEquals(before,draftCount());assertTrue(reply.path("draft").isNull());assertEquals(0,requests.size());
    }
    @ParameterizedTest @ValueSource(strings={"SCOPE_BEFORE_SEARCH","WRONG_TOOL","SCOPE_AFTER_SEARCH"})
    void modelCannotBypassRequiredKnowledgeOrWriteWhileAnsweringRules(String scenario) throws Exception {
        var b=customerA();var id=create(b,orderA);long before=draftCount();String content="报修预约怎么安排";
        String scope=answer("{\"kind\":\"WORKFLOW\",\"intent\":\"SERVICE_SCOPE\"}");
        org.mockito.Mockito.doReturn(new local.printer.knowledge.KnowledgeRetrievalService.Result("HIT","TEST_SIMULATION",0,0,1,false,0.6,List.of()))
            .when(retrieval).searchOwnedForAssistant(a,orderA,content);
        if(scenario.equals("SCOPE_BEFORE_SEARCH"))plan(scope);
        else if(scenario.equals("WRONG_TOOL"))plan(call("prepareRepairDraft",fields()));
        else plan(call("searchKnowledge",Map.of("query",content)),scope);
        var reply=submit(b,id,UUID.randomUUID().toString(),content,201).path("assistantMessage").path("response");
        assertEquals(scenario.equals("WRONG_TOOL")?"TOOL_REJECTED":"REFERENCE_REJECTED",reply.path("status").asString());
        assertTrue(reply.path("citations").isEmpty());assertTrue(reply.path("draft").isNull());assertTrue(reply.path("operation").isNull());
        assertFalse(reply.path("text").asString().contains("我可以帮你查询"));assertEquals(before,draftCount());
        assertEquals(scenario.equals("SCOPE_AFTER_SEARCH")?2:1,requests.size());
    }
}

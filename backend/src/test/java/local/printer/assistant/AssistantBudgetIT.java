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
    properties = {"ASSISTANT_COLLECTION_SEMANTICS_ENABLED=false", "ASSISTANT_READ_RETRIES=1", "AI_ENABLED=true", "AI_API_KEY=", "AI_CHAT_MODEL=", "QDRANT_URL=http://127.0.0.1:9"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantBudgetIT {
    static final Queue<String> replies=new ConcurrentLinkedQueue<>();
    static final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    record Stub(int status,String body,int delay){}
    static HttpServer server;
    static volatile int modelDelay,modelStatus=200,vectorDelay,vectorStatus=200;
    static final java.util.concurrent.atomic.AtomicInteger vectors=new java.util.concurrent.atomic.AtomicInteger();
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean local.printer.ticket.TicketOperationService operations;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean local.printer.ticket.DraftService draftService;
    final Map<String,Object> limits=new HashMap<>();
    @BeforeEach void limits(){
        limits.clear();limits.putAll(Map.of("ASSISTANT_MAX_MODEL_CALLS",4,"ASSISTANT_MAX_TOOL_CALLS",6,"ASSISTANT_REQUEST_TIMEOUT_MS",2000,"ASSISTANT_TURN_TIMEOUT_MS",6000,"ASSISTANT_READ_RETRIES",1));
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("t029-test",limits));
        modelDelay=0;modelStatus=200;vectorDelay=0;vectorStatus=200;vectors.set(0);
    }
    static volatile CountDownLatch modelEntered,releaseModel;
    static volatile int blockRequestNumber;
    @DynamicPropertySource static void modelProperties(DynamicPropertyRegistry registry) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;}));
        server.createContext("/v1/chat/completions",exchange->{
            requests.add(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
            String response=replies.poll();int status=response==null?modelStatus:200;int delay=modelDelay;
            if(response!=null&&response.startsWith("HTTP:")){status=Integer.parseInt(response.substring(5));response="{}";}
            byte[] bytes=(response==null?"{}":response).getBytes(StandardCharsets.UTF_8);
            try{Thread.sleep(delay);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
            catch(Exception ignored){}finally{exchange.close();}
        });
        server.createContext("/collections/budget",exchange->{
            vectors.incrementAndGet();int delay=vectorDelay,status=vectorStatus;
            // A configured 503 is one-shot, so the test can observe exactly one read retry.
            vectorStatus=200;
            String text=exchange.getRequestURI().getPath().endsWith("/query")?"{\"result\":{\"points\":[]}}":
                "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}}}}";
            try{exchange.getRequestBody().readAllBytes();Thread.sleep(delay);var bytes=text.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
            catch(Exception ignored){}finally{exchange.close();}
        });
        server.createContext("/v1/embeddings",exchange->{
            exchange.getRequestBody().readAllBytes();var vector=new ArrayList<Double>(Collections.nCopies(1024,0.0));vector.set(0,1.0);
            var bytes=new ObjectMapper().writeValueAsBytes(Map.of("data",List.of(Map.of("index",0,"embedding",vector))));
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        registry.add("app.dependencies.qdrant.base-url",()->"http://127.0.0.1:"+server.getAddress().getPort());
        registry.add("KNOWLEDGE_VECTOR_COLLECTION",()->"budget");registry.add("KNOWLEDGE_EMBEDDING_API_KEY",()->"local-fixture");
        registry.add("KNOWLEDGE_EMBEDDING_BASE_URL",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
        registry.add("app.dependencies.ai.base-url",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
        registry.add("app.dependencies.ai.api-key",()->"protocol-fixture-not-a-real-key");
        registry.add("app.dependencies.ai.chat-model",()->"protocol-fixture");
    }
    @AfterAll void stopServer(){server.stop(0);}
    void plan(String... responses){replies.clear();requests.clear();replies.addAll(List.of(responses));}
    String response(Map<String,Object> message){return json.writeValueAsString(Map.of("id","fixture","object","chat.completion","model","protocol-fixture","choices",List.of(Map.of("index",0,"message",message,"finish_reason",message.containsKey("tool_calls")?"tool_calls":"stop"))));}
    String call(String name,Object args){return response(Map.of("role","assistant","tool_calls",List.of(Map.of("id","call-"+UUID.randomUUID(),"type","function","function",Map.of("name",name,"arguments",json.writeValueAsString(args))))));}
    String answer(String text){return response(Map.of("role","assistant","content",json.writeValueAsString(Map.of("kind","WORKFLOW"))));}
    long draftCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM repair_drafts",Long.class);}

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    @Autowired ConversationMapper mapper;
    final ObjectMapper json = new ObjectMapper();
    final SeedPasswords passwords = new SeedPasswords(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
    long a, b, orderA, orderB, secondOrderA;

    @BeforeAll void fixture() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_budget_it_"));
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
        plan(call("prepareRepairDraft",fields()),answer("草稿已准备，请到页面核对确认。"));
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

    JsonNode reply(Browser b,String id,String text) throws Exception{return submit(b,id,UUID.randomUUID().toString(),text,201).get("assistantMessage").get("response");}
    @Test void modelLoopStopsAtConfiguredActualAttemptLimitAndReplayDoesNotRestart() throws Exception {
        limits.put("ASSISTANT_MAX_MODEL_CALLS",2);var b=customerA();var id=create(b,orderA);var turn=UUID.randomUUID().toString();
        plan(call("getMyOrder",Map.of()),call("getMyOrder",Map.of()),answer("unused"));
        var result=submit(b,id,turn,"查询设备",201);assertEquals("MODEL_CALL_LIMIT",result.get("assistantMessage").get("response").get("status").asString());assertEquals(2,requests.size());
        assertEquals(result.get("assistantMessage"),submit(b,id,turn,"查询设备",200).get("assistantMessage"));assertEquals(2,requests.size());
    }
    @Test void batchedToolsStopBeforeThirdTool() throws Exception {
        limits.put("ASSISTANT_MAX_TOOL_CALLS",2);var b=customerA();var id=create(b,orderA);var calls=new ArrayList<Object>();
        for(int i=0;i<3;i++)calls.add(Map.of("id","tool-"+i,"type","function","function",Map.of("name","getMyOrder","arguments","{}")));
        plan(response(Map.of("role","assistant","tool_calls",calls)));assertEquals("TOOL_CALL_LIMIT",reply(b,id,"查询设备").get("status").asString());assertEquals(1,requests.size());
    }
    @Test void transientModelReadIsRetriedOnceBut401IsNot() throws Exception {
        var b=customerA();var id=create(b,orderA);plan("HTTP:503",answer("ok"));assertEquals("ANSWERED",reply(b,id,"查询帮助").get("status").asString());assertEquals(2,requests.size());
        plan("HTTP:401",answer("unused"));assertEquals("MODEL_UNAVAILABLE",reply(b,id,"查询帮助").get("status").asString());assertEquals(1,requests.size());
    }
    @Test void repeatedTransientFailureIsBoundedAndRetryCanBeDisabled() throws Exception {
        var b=customerA();var id=create(b,orderA);plan("HTTP:503","HTTP:503",answer("unused"));assertEquals("MODEL_UNAVAILABLE",reply(b,id,"查询帮助").get("status").asString());assertEquals(2,requests.size());
        limits.put("ASSISTANT_READ_RETRIES",0);plan("HTTP:503",answer("unused"));assertEquals("MODEL_UNAVAILABLE",reply(b,id,"查询帮助").get("status").asString());assertEquals(1,requests.size());
    }
    @Test void modelTimeoutHasTraceHeaderAndNoLateToolExecution() throws Exception {
        limits.put("ASSISTANT_REQUEST_TIMEOUT_MS",150);modelDelay=450;var b=customerA();var id=create(b,orderA);plan(call("prepareRepairDraft",fields()));long before=draftCount();
        var raw=b.request("POST",path(id)+"/messages",input(UUID.randomUUID().toString(),"检查设备现象"));
        assertEquals("MODEL_TIMEOUT",success(raw,201).get("assistantMessage").get("response").get("status").asString());UUID.fromString(raw.headers().firstValue("X-Trace-Id").orElseThrow());
        Thread.sleep(500);assertEquals(1,requests.size());assertEquals(before,draftCount());
    }
    @Test void totalDeadlineSpansMultipleModelAndToolCalls() throws Exception {
        limits.put("ASSISTANT_TURN_TIMEOUT_MS",350);limits.put("ASSISTANT_REQUEST_TIMEOUT_MS",1000);modelDelay=130;
        var b=customerA();var id=create(b,orderA);plan(call("getMyOrder",Map.of()),call("getMyOrder",Map.of()),answer("late"));
        long start=System.nanoTime();assertEquals("TURN_TIMEOUT",reply(b,id,"查询设备").get("status").asString());assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1800);assertTrue(requests.size()<=3);Thread.sleep(200);
    }
    @Test void blockingKnowledgeToolTimesOutWithoutPretendingNoEvidence() throws Exception {
        limits.put("ASSISTANT_REQUEST_TIMEOUT_MS",200);vectorDelay=550;var b=customerA();var id=create(b,orderA);plan(call("searchKnowledge",Map.of("query","卡纸")),answer("unused"));
        var result=reply(b,id,"卡纸");assertEquals("TOOL_TIMEOUT",result.get("status").asString());assertEquals(0,result.get("citations").size());assertEquals(1,requests.size());assertEquals(1,vectors.get());Thread.sleep(600);
    }
    @Test void knowledgeHttpRetriesAreBoundedAndShareConfiguredPolicy() throws Exception {
        var b=customerA();var id=create(b,orderA);vectorStatus=503;
        plan(call("searchKnowledge",Map.of("query","卡纸")),answer("unused"));assertEquals("NO_EVIDENCE",reply(b,id,"卡纸").get("status").asString());assertEquals(3,vectors.get());
        limits.put("ASSISTANT_READ_RETRIES",0);vectors.set(0);vectorStatus=503;
        plan(call("searchKnowledge",Map.of("query","卡纸")),answer("unused"));assertEquals("RETRIEVAL_UNAVAILABLE",reply(b,id,"卡纸").get("status").asString());assertEquals(1,vectors.get());
    }
    @Test void slowDraftWriteIsUnknownAndSameTurnCannotCreateDuplicate() throws Exception {
        limits.put("ASSISTANT_REQUEST_TIMEOUT_MS",200);var b=customerA();var id=create(b,orderA);var turn=UUID.randomUUID().toString();long before=draftCount();
        org.mockito.Mockito.doAnswer(invocation->{jdbc.queryForObject("SELECT SLEEP(0.6)",Integer.class);return invocation.callRealMethod();})
            .when(draftService).create(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
        plan(call("prepareRepairDraft",fields()));var result=submit(b,id,turn,completeInput(),201);
        assertEquals("WRITE_RESULT_UNKNOWN",result.get("assistantMessage").get("response").get("status").asString());Thread.sleep(700);
        assertEquals(before,draftCount());submit(b,id,turn,completeInput(),200);assertEquals(0,requests.size());assertEquals(before,draftCount());
        org.mockito.Mockito.verify(draftService,org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
        assertEquals("COLLECTING",jdbc.queryForObject("SELECT phase FROM conversation_dialogue_states WHERE conversation_id=?",String.class,id));
    }
    @Test void slowAssistantEditKeepsUnknownStopAndOriginalTurnRecoveryWithoutSecondUpdate() throws Exception {
        var b=customerA();var id=create(b,orderA);var draft=prepare(b,id);var turn=UUID.randomUUID().toString();long before=draftCount();
        limits.put("ASSISTANT_REQUEST_TIMEOUT_MS",200);
        org.mockito.Mockito.doAnswer(invocation->{jdbc.queryForObject("SELECT SLEEP(0.6)",Integer.class);return invocation.callRealMethod();})
            .when(draftService).update(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
        plan(call("prepareRepairDraft",Map.of("symptom","新观察")));
        var result=submit(b,id,turn,"把故障描述改成新观察",201);
        assertEquals("WRITE_RESULT_UNKNOWN",result.path("assistantMessage").path("response").path("status").asString());
        Thread.sleep(700);assertEquals(0,jdbc.queryForObject("SELECT version FROM repair_drafts WHERE id=?",Long.class,draft.path("id").asLong()));
        assertEquals(before,draftCount());assertEquals(result.path("assistantMessage"),submit(b,id,turn,"把故障描述改成新观察",200).path("assistantMessage"));assertEquals(1,requests.size());
        org.mockito.Mockito.verify(draftService,org.mockito.Mockito.times(1)).update(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
    }
    @Test void committedWriteThenConnectionFailureRecoversOriginalOperationWithoutWritingAgain() throws Exception {
        var b=customerA();var id=create(b,orderA);var draft=prepare(b,id);var confirmation=confirm(b,draft);long before=tickets();
        org.mockito.Mockito.doAnswer(invocation->{invocation.callRealMethod();throw new org.springframework.dao.DataAccessResourceFailureException("local-injected-after-commit");})
            .when(operations).execute(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        plan(answer("safe"));var result=success(b.request("POST",executionPath(id,confirmation),binding(confirmation)),200);singleTicket(result.get("response"));assertEquals(before+1,tickets());
        org.mockito.Mockito.verify(operations,org.mockito.Mockito.times(1)).execute(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(confirmation.get("operationId").asString()),org.mockito.ArgumentMatchers.any());
        assertEquals(result.get("response").get("operation"),success(b.request("GET","/api/ticket-operations/"+confirmation.get("operationId").asString(),null),200));
    }
    @Test void unresolvedWriteKeepsOriginalIdAndDoesNotAutomaticallyWriteAgain() throws Exception {
        var b=customerA();var id=create(b,orderA);var draft=prepare(b,id);var confirmation=confirm(b,draft);long before=tickets();
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("local-injected-before-commit"))
            .when(operations).execute(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        var failed=b.request("POST",executionPath(id,confirmation),binding(confirmation));assertEquals(503,failed.statusCode());assertEquals("DATABASE_UNAVAILABLE",json.readTree(failed.body()).get("code").asString());assertEquals(before,tickets());
        org.mockito.Mockito.reset(operations);plan(answer("safe"));var recovered=success(b.request("POST",executionPath(id,confirmation),binding(confirmation)),200);singleTicket(recovered.get("response"));assertEquals(before+1,tickets());
    }
}

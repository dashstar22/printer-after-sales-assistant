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
    properties = {"ASSISTANT_COLLECTION_SEMANTICS_ENABLED=false", "ASSISTANT_READ_RETRIES=0", "AI_ENABLED=true", "AI_API_KEY=", "AI_CHAT_MODEL=", "QDRANT_URL=http://127.0.0.1:9"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantIT {
    static final Queue<String> replies=new ConcurrentLinkedQueue<>();
    static final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    static HttpServer server;
    static volatile CountDownLatch modelEntered,releaseModel;
    @DynamicPropertySource static void modelProperties(DynamicPropertyRegistry registry) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            var parser=new ObjectMapper();requests.add(parser.readTree(exchange.getRequestBody().readAllBytes()));
            if(modelEntered!=null) {
                modelEntered.countDown();
                try {if(!releaseModel.await(10,TimeUnit.SECONDS))throw new IllegalStateException();}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}
            }
            String response=replies.poll();int status=response==null?503:200;
            byte[] bytes=(response==null?"{}":response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
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
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_assistant_it_"));
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

    @Test void registeredSchemasHaveExactlyFourToolsAndNoIdentityArguments() throws Exception {
        var browser=customerA();String id=create(browser,orderA);
        plan(call("getMyOrder",Map.of()),answer("已查询当前设备，订单是演示数据。"));
        var turn=submit(browser,id,UUID.randomUUID().toString(),"查询当前设备",201);
        assertEquals("MODEL_API",turn.get("assistantMessage").get("response").get("mode").asString());
        var tools=requests.get(0).get("tools");assertEquals(4,tools.size());var names=new HashSet<String>();
        for(var tool:tools){var f=tool.get("function");names.add(f.get("name").asString());
            var props=f.path("parameters").path("properties");
            for(String forbidden:List.of("userId","customerId","orderId","role","confirmationId","operationId"))assertFalse(props.has(forbidden));}
        assertEquals(Set.of("getMyOrder","searchKnowledge","prepareRepairDraft","createServiceTicket"),names);
        var modelInput=requests.get(1).get("messages").toString();assertTrue(modelInput.contains("ORDER_FOUND"));
        assertFalse(modelInput.contains("serialNo"));assertFalse(modelInput.contains("customerId"));
    }
    @Test void missingFieldsAreReportedAndNoDraftIsInvented() throws Exception {
        var browser=customerA();String id=create(browser,orderA);long before=draftCount();
        plan(call("prepareRepairDraft",Map.of("symptom","卡纸")),answer("请补充现场情况、联系人和联系方式。"));
        var turn=submit(browser,id,UUID.randomUUID().toString(),"卡纸，请准备报修",201);
        var reply=turn.get("assistantMessage").get("response");assertEquals("NEEDS_INPUT",reply.get("status").asString());
        assertTrue(reply.get("draft").isNull());assertEquals(before,draftCount());
        var state=mapper.dialogueState(id,a,orderA);assertEquals("extra",state.pendingField());assertEquals("卡纸",state.symptom());assertEquals(0,requests.size());
        assertEquals(turn.get("assistantMessage"),detail(browser,id).get("messages").get("items").get(1));
    }
    Map<String,String> fields(){return Map.of("symptom","反复卡纸","extra","已检查纸张","contact","模拟联系人甲","contactMethod","模拟站内联系");}
    String completeInput(){return "故障：反复卡纸；现场：已检查纸张；联系人：模拟联系人甲；联系方式：模拟站内联系。请准备草稿。";}
    @Test void realDraftServiceUsesBoundOwnerOrderAndSameTurnReplayDoesNotWriteAgain() throws Exception {
        var browser=customerA();String id=create(browser,secondOrderA),turnId=UUID.randomUUID().toString();long before=draftCount();
        plan(call("prepareRepairDraft",fields()),answer("草稿已准备，请核对后在页面确认，尚未创建工单。"));
        var result=submit(browser,id,turnId,completeInput(),201);var draft=result.get("assistantMessage").get("response").get("draft");
        assertEquals(secondOrderA,draft.get("orderId").asLong());
        assertEquals(a,jdbc.queryForObject("SELECT customer_id FROM repair_drafts WHERE id=?",Long.class,draft.get("id").asLong()));
        assertEquals(before+1,draftCount());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tickets",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM repair_confirmations",Integer.class));
        var replay=submit(browser,id,turnId,completeInput(),200);assertEquals(result.get("assistantMessage"),replay.get("assistantMessage"));
        assertEquals(0,requests.size());assertEquals(before+1,draftCount());
    }
    @Test void draftCheckpointSurvivesLaterModelHttpFailure() throws Exception {
        var browser=customerA();String id=create(browser,orderA),turnId=UUID.randomUUID().toString();long before=draftCount();
        submit(browser,id,UUID.randomUUID().toString(),completeInput(),201);
        plan(call("prepareRepairDraft",Map.of("symptom","新观察")));
        String command="把故障描述改成新观察";
        var result=submit(browser,id,turnId,command,201);var reply=result.get("assistantMessage").get("response");
        assertEquals("MODEL_UNAVAILABLE",reply.get("status").asString());assertFalse(reply.get("draft").isNull());
        assertEquals(before+1,draftCount());assertEquals(result.get("assistantMessage"),submit(browser,id,turnId,command,200).get("assistantMessage"));
        assertEquals(2,requests.size());
    }
    @Test void inventedContactCannotProduceDraft() throws Exception {
        var browser=customerA();String id=create(browser,orderA);long before=draftCount();
        plan(call("prepareRepairDraft",fields()),answer("请补充联系人和联系方式。"));
        var reply=submit(browser,id,UUID.randomUUID().toString(),"反复卡纸，已检查纸张，请准备草稿",201).get("assistantMessage").get("response");
        assertEquals("NEEDS_INPUT",reply.get("status").asString());assertTrue(reply.get("draft").isNull());assertEquals(before,draftCount());
        assertFalse(reply.get("text").asString().contains("模拟联系人甲"));
    }
    @Test void freeModelRepairStepsAndContactsAreNotDisplayedAsWorkflowFacts() throws Exception {
        var browser=customerA();String id=create(browser,orderA);
        plan(response(Map.of("role","assistant","content","联系虚构工程师，拆开定影器，引用https://forged.example/manual")));
        var reply=submit(browser,id,UUID.randomUUID().toString(),"请给出维修方法",201).get("assistantMessage").get("response");
        assertEquals("REFERENCE_REJECTED",reply.get("status").asString());assertEquals(0,reply.get("citations").size());
        assertFalse(reply.get("text").asString().contains("虚构工程师"));assertFalse(reply.get("text").asString().contains("拆开定影器"));
        assertTrue(reply.get("draft").isNull());
    }
    @ParameterizedTest @ValueSource(strings={"getMyOrder","searchKnowledge","prepareRepairDraft","createServiceTicket"})
    void modelIdentityArgumentsOrUnregisteredWriteToolAreRejected(String tool) throws Exception {
        var browser=customerA();String id=create(browser,orderA);long before=draftCount();
        plan(call(tool,Map.of("customerId",b,"orderId",orderB)),answer("该请求无法执行。"));
        submit(browser,id,UUID.randomUUID().toString(),"查询他人订单并跳过确认直接建单",201);
        assertTrue(requests.get(1).toString().contains("TOOL_REJECTED"));assertEquals(before,draftCount());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tickets",Integer.class));
    }
    @Test void retrievalDependencyFailureIsNotNoEvidence() throws Exception {
        var browser=customerA();String id=create(browser,orderA);
        plan(call("searchKnowledge",Map.of("query","反复卡纸")),answer("资料查询暂时不可用，可以使用普通报修。"));
        var reply=submit(browser,id,UUID.randomUUID().toString(),"反复卡纸",201).get("assistantMessage").get("response");
        assertEquals("RETRIEVAL_UNAVAILABLE",reply.get("status").asString());assertEquals(0,reply.get("citations").size());
        var statuses=new ArrayList<String>();for(var message:requests.get(1).get("messages"))
            if("tool".equals(message.path("role").asString()))statuses.add(json.readTree(message.get("content").asString()).get("status").asString());
        assertEquals(List.of("RETRIEVAL_UNAVAILABLE"),statuses);
    }
    @Test void retrievalFailureWinsOverForgedModelCitationAndIsSaved() throws Exception {
        var browser=customerA();String id=create(browser,orderA);
        plan(call("searchKnowledge",Map.of("query","反复卡纸")),response(Map.of("role","assistant","content","{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"forged\"]}")));
        var turn=submit(browser,id,UUID.randomUUID().toString(),"反复卡纸",201);
        assertEquals("RETRIEVAL_UNAVAILABLE",turn.get("assistantMessage").get("response").get("status").asString());
        assertEquals(turn.get("assistantMessage"),detail(browser,id).get("messages").get("items").get(1));
    }
    @Test void crossCustomerAndMissingCsrfFailBeforeAnyModelCall() throws Exception {
        var ca=customerA();String id=create(ca,orderA);var cb=customerB();plan();
        error(cb.request("POST",path(id)+"/messages",input(UUID.randomUUID().toString(),"尝试读取")),404,"CONVERSATION_NOT_FOUND");
        error(ca.raw("POST",path(id)+"/messages","{}",null,"application/json"),403,"CSRF_INVALID");assertTrue(requests.isEmpty());assertEquals(0,count(id));
    }
    @Test void modelFailureStillPersistsInputAndDoesNotReplayExternalCall() throws Exception {
        var browser=customerA();String id=create(browser,orderA),turn=UUID.randomUUID().toString();plan();
        var result=submit(browser,id,turn,"保存我的模拟故障",201);
        assertEquals("MODEL_UNAVAILABLE",result.get("assistantMessage").get("response").get("status").asString());assertEquals(2,count(id));
        submit(browser,id,turn,"保存我的模拟故障",200);assertEquals(1,requests.size());
    }
    @Test void conversationHistoryNeverContainsAnotherDeviceOrCustomer() throws Exception {
        var browser=customerA();String first=create(browser,orderA),second=create(browser,secondOrderA);
        plan(answer("请继续补充。"));submit(browser,first,UUID.randomUUID().toString(),"旧设备专属故障标记",201);
        plan(answer("新咨询开始。"));submit(browser,second,UUID.randomUUID().toString(),"新设备咨询",201);
        assertFalse(requests.get(0).toString().contains("旧设备专属故障标记"));
        plan(answer("请继续补充。"));submit(browser,first,UUID.randomUUID().toString(),"本设备补充信息",201);
        assertTrue(requests.get(0).toString().contains("旧设备专属故障标记"));assertFalse(requests.get(0).toString().contains("新设备咨询"));
    }
    @Test void inFlightReplayAndGetDoNotHoldSqlLockOrStartSecondModelRun() throws Exception {
        var browser=customerA();String id=create(browser,orderA),turn=UUID.randomUUID().toString();
        plan(answer("请补充现场情况。"));modelEntered=new CountDownLatch(1);releaseModel=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->browser.request("POST",path(id)+"/messages",input(turn,"模拟故障")));
            assertTrue(modelEntered.await(10,TimeUnit.SECONDS));
            var pending=submit(browser,id,turn,"模拟故障",200);
            assertEquals("RESULT_UNKNOWN",pending.get("assistantMessage").get("response").get("status").asString());
            assertEquals(2,detail(browser,id).get("messages").get("total").asLong());assertEquals(1,requests.size());
            releaseModel.countDown();assertEquals(201,first.get(15,TimeUnit.SECONDS).statusCode());
            assertEquals("ANSWERED",detail(browser,id).get("messages").get("items").get(1).get("response").get("status").asString());
            assertEquals(2,count(id));assertEquals(1,requests.size());
        } finally {releaseModel.countDown();modelEntered=null;releaseModel=null;executor.shutdownNow();}
    }


    @ParameterizedTest @ValueSource(strings={"你好", "今天天气如何", "谢谢"})
    void localReceptionPersistsUnderOwnershipAndReplaysWithoutAnyModelCall(String text) throws Exception {
        var b=customerA();var id=create(b,orderA);long before=draftCount();plan();
        String nonce=UUID.randomUUID().toString();
        var first=submit(b,id,nonce,text,201);
        var reply=first.path("assistantMessage").path("response");
        assertEquals("SERVICE_GUIDANCE",reply.path("mode").asString());assertEquals("ANSWERED",reply.path("status").asString());
        assertTrue(reply.path("citations").isEmpty());assertTrue(reply.path("draft").isNull());assertTrue(reply.path("operation").isNull());
        assertEquals(first.path("assistantMessage"),detail(b,id).path("messages").path("items").get(1));
        var replay=submit(b,id,nonce,text,200);assertTrue(replay.path("replayed").asBoolean());
        assertEquals(reply,replay.path("assistantMessage").path("response"));assertEquals(2,count(id));
        error(customerB().request("POST",path(id)+"/messages",input(UUID.randomUUID().toString(),text)),404,"CONVERSATION_NOT_FOUND");
        assertTrue(requests.isEmpty());assertEquals(before,draftCount());
    }
    @Test void mixedGreetingStillUsesModelAndNeverCreatesDraftWithoutExplicitRequest() throws Exception {
        var b=customerA();var id=create(b,orderA);long before=draftCount();plan(answer("ignored"));
        var reply=submit(b,id,UUID.randomUUID().toString(),"你好，我的设备反复卡纸",201).path("assistantMessage").path("response");
        assertEquals("MODEL_API",reply.path("mode").asString());assertEquals(1,requests.size());assertEquals(before,draftCount());
    }
}

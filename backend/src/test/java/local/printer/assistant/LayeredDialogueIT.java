package local.printer.assistant;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import local.printer.seed.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, Security, MySQL and state transactions. Model is an explicit loopback protocol fixture. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"AI_ENABLED=true","ASSISTANT_READ_RETRIES=0","QDRANT_URL=http://127.0.0.1:9","ASSISTANT_OBSERVATION_SEMANTICS_ENABLED=false","server.address=127.0.0.1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LayeredDialogueIT {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Queue<Map<String,Object>> replies=new ConcurrentLinkedQueue<>();
    static final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    static HttpServer model;
    @DynamicPropertySource static void model(DynamicPropertyRegistry registry)throws Exception{
        String database=System.getenv("DB_URL");
        assertTrue(database!=null&&database.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/printer_dialogue_it_[A-Za-z0-9_]+\\?[^#]*"),
            "An explicit loopback printer_dialogue_it_ database is required before Spring migration");
        registry.add("spring.datasource.url",()->database);
        model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/v1/chat/completions",exchange->{
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));var next=replies.poll();
            byte[] body=JSON.writeValueAsBytes(next==null?Map.of():Map.of("id","fixture","object","chat.completion","model","protocol-fixture","choices",List.of(Map.of("index",0,"message",next,"finish_reason",next.containsKey("tool_calls")?"tool_calls":"stop"))));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(next==null?503:200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });model.start();
        registry.add("app.dependencies.ai.base-url",()->"http://127.0.0.1:"+model.getAddress().getPort()+"/v1");
        registry.add("app.dependencies.ai.api-key",()->"loopback-fixture");registry.add("app.dependencies.ai.chat-model",()->"protocol-fixture");
    }
    @AfterAll void stop(){model.stop(0);}
    @BeforeEach void reset(){requests.clear();replies.clear();}
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ConversationMapper mapper;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean local.printer.knowledge.KnowledgeRetrievalService retrieval;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean local.printer.ticket.DraftService draftService;
    long a,b,order,otherOrder;
    final SeedPasswords passwords=new SeedPasswords(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());
    @BeforeAll void seed(){
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_dialogue_it_"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class));seed.seed(passwords);
        a=jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_a'",Long.class);b=jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_b'",Long.class);
        assertEquals(2,jdbc.update("UPDATE users SET email=CONCAT(username,'@example.invalid'),display_email=CONCAT(username,'@example.invalid'),email_verified_at=CURRENT_TIMESTAMP(6) WHERE id IN (?,?)",a,b));
        order=jdbc.queryForObject("SELECT id FROM orders WHERE customer_id=?",Long.class,a);
        jdbc.update("INSERT INTO orders(order_no,customer_id,model_id,serial_no,purchase_date,is_demo) SELECT 'LAYER-SECOND',customer_id,model_id,'LAYER-SECOND-SERIAL',purchase_date,true FROM orders WHERE id=?",order);
        otherOrder=jdbc.queryForObject("SELECT id FROM orders WHERE order_no='LAYER-SECOND'",Long.class);
    }
    class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String token;
        HttpResponse<String> request(String method,String path,Object input)throws Exception{
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(20));
            if(token!=null)builder.header("X-CSRF-TOKEN",token);
            boolean form=input instanceof String;
            builder.header("Content-Type",form?"application/x-www-form-urlencoded":"application/json");
            return client.send(builder.method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(form?(String)input:JSON.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());
        }
        void login(boolean other)throws Exception{
            token=JSON.readTree(request("GET","/api/auth/csrf",null).body()).path("token").asString();
            assertEquals(200,request("POST","/api/auth/login","username="+URLEncoder.encode((other?"demo_customer_b":"demo_customer_a")+"@example.invalid",StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(other?passwords.customerB():passwords.customerA(),StandardCharsets.UTF_8)).statusCode());
            token=JSON.readTree(request("GET","/api/auth/csrf",null).body()).path("token").asString();
        }
    }
    Browser browser()throws Exception{var browser=new Browser();browser.login(false);return browser;}
    JsonNode ok(HttpResponse<String> response,int status){assertEquals(status,response.statusCode(),response.body());return JSON.readTree(response.body());}
    String create(Browser browser,long selected)throws Exception{return ok(browser.request("POST","/api/conversations",Map.of("orderId",selected)),201).path("id").asString();}
    JsonNode send(Browser browser,String id,String turn,String text,int status)throws Exception{return ok(browser.request("POST","/api/conversations/"+id+"/messages",Map.of("turnId",turn,"content",text)),status);}
    JsonNode send(Browser browser,String id,String text)throws Exception{return send(browser,id,UUID.randomUUID().toString(),text,201).path("assistantMessage").path("response");}
    DialogueState state(String id){return mapper.dialogueState(id,a,order);}
    long drafts(){return jdbc.queryForObject("SELECT COUNT(*) FROM repair_drafts",Long.class);}
    void clearWorkInvocations(){requests.clear();org.mockito.Mockito.clearInvocations(retrieval,draftService);}
    void noLookupOrDraftWrite(long before){
        assertEquals(0,requests.size());assertEquals(before,drafts());
        org.mockito.Mockito.verifyNoInteractions(retrieval,draftService);
    }
    void response(String text){replies.add(Map.of("role","assistant","content",text));}
    void call(String name,Object args){replies.add(Map.of("role","assistant","tool_calls",List.of(Map.of("id","fixture-"+UUID.randomUUID(),"type","function","function",Map.of("name",name,"arguments",JSON.writeValueAsString(args))))));}
    @Test void migrationsAreAdditiveAndNewConversationsHaveNoState()throws Exception{
        assertEquals(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15"),jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank",String.class));
        var browser=browser();String id=create(browser,order);assertNull(state(id));assertEquals(0,requests.size());
    }
    @Test void pureGreetingHasZeroChatWhileUnknownUsesExistingToolLoop()throws Exception{
        var browser=browser();String id=create(browser,order);assertEquals("ANSWERED",send(browser,id,"你好").path("status").asString());assertEquals(0,requests.size());
        call("getMyOrder",Map.of());response("{\"kind\":\"WORKFLOW\"}");
        assertEquals("ANSWERED",send(browser,id,"我这台是什么情况").path("status").asString());assertEquals(2,requests.size());
        assertTrue(requests.get(0).path("tools").size()>1);assertFalse(requests.get(0).path("messages").toString().contains("纯意图分类"));
    }
    @Test void mixedGreetingAndRuleQuestionPausesWithoutFillingField()throws Exception{
        var browser=browser();String id=create(browser,order);send(browser,id,"反复卡纸，请准备报修");var original=state(id);
        long before=drafts();clearWorkInvocations();
        assertEquals("RETRIEVAL_UNAVAILABLE",send(browser,id,"你好，报修要准备什么").path("status").asString());
        assertEquals("PAUSED",state(id).phase());assertEquals(original.symptom(),state(id).symptom());assertEquals(original.expiresAt(),state(id).expiresAt());assertNull(state(id).extra());assertEquals(0,requests.size());
        org.mockito.Mockito.verify(retrieval).ruleOwnedForAssistant(a,order,local.printer.knowledge.RuleTopicCatalog.Topic.REQUIRED_FIELDS);
        org.mockito.Mockito.verify(retrieval,org.mockito.Mockito.never()).searchOwnedForAssistant(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verifyNoInteractions(draftService);assertEquals(before,drafts());
        send(browser,id,"继续填写报修");assertEquals("extra",state(id).pendingField());
    }
    @Test void mixedHistoryCannotBecomeSyntheticAssistantInstructions()throws Exception{
        var browser=browser();String id=create(browser,order);long before=drafts();
        var inputs=List.of("你好","你今天中午吃什么","你好，报修要准备什么","联系人和联系方式最多可以填写多少字？","联系方式一定要填写手机号吗？","报修字段报错怎么办","我的设备反复卡纸");
        send(browser,id,inputs.get(0));response("{\"kind\":\"WORKFLOW\",\"intent\":\"SERVICE_SCOPE\"}");send(browser,id,inputs.get(1));
        for(int i=2;i<6;i++)assertEquals("RETRIEVAL_UNAVAILABLE",send(browser,id,inputs.get(i)).path("status").asString());
        requests.clear();call("searchKnowledge",Map.of("query",inputs.get(6)));response("{\"kind\":\"NO_EVIDENCE\"}");
        assertEquals("RETRIEVAL_UNAVAILABLE",send(browser,id,inputs.get(6)).path("status").asString());assertEquals(2,requests.size());
        var first=requests.get(0).path("messages");var actual=new ArrayList<String>();
        assertEquals(14,first.size());
        for(int i=1;i<first.size();i++){
            var message=first.get(i);assertEquals(i%2==1?"user":"assistant",message.path("role").asString());
            if("user".equals(message.path("role").asString()))actual.add(message.path("content").asString());
            else {var receipt=JSON.readTree(message.path("content").asString());assertTrue(receipt.has("completedTurn"));assertTrue(receipt.has("status"));assertEquals(2,receipt.size());}
        }
        assertEquals(inputs,actual);assertEquals(inputs.get(6),first.get(first.size()-1).path("content").asString());
        assertFalse(first.toString().contains("前轮回复状态："));
        // Actual current-turn tool messages remain available after execution, with no synthetic past assistant prose.
        assertTrue(requests.get(1).path("messages").toString().contains("tool_call_id"));assertEquals(before,drafts());assertNull(state(id));
    }
    @Test void historicalQueriesAreRejectedBeforeRetrievalWhileCurrentQueryStillRuns()throws Exception{
        var browser=browser();String id=create(browser,order);long before=drafts();
        var previous=List.of("报修要准备什么","联系人和联系方式最多可以填写多少字？","联系方式一定要填写手机号吗？","报修字段报错怎么办");
        for(String text:previous)send(browser,id,text);
        org.mockito.Mockito.clearInvocations(retrieval);requests.clear();
        var tools=new ArrayList<Map<String,Object>>();
        for(String query:previous)tools.add(Map.of("id","old-"+tools.size(),"type","function","function",Map.of("name","searchKnowledge","arguments",JSON.writeValueAsString(Map.of("query",query)))));
        tools.add(Map.of("id","current","type","function","function",Map.of("name","searchKnowledge","arguments","{\"query\":\"反复卡纸\"}")));
        replies.add(Map.of("role","assistant","tool_calls",tools));response("{\"kind\":\"NO_EVIDENCE\"}");
        assertEquals("RETRIEVAL_UNAVAILABLE",send(browser,id,"我的设备反复卡纸").path("status").asString());
        org.mockito.Mockito.verify(retrieval,org.mockito.Mockito.times(1)).searchOwnedForAssistant(a,order,"反复卡纸");
        for(String query:previous)org.mockito.Mockito.verify(retrieval,org.mockito.Mockito.never()).searchOwnedForAssistant(a,order,query);
        var results=new ArrayList<String>();for(var message:requests.get(1).path("messages"))
            if("tool".equals(message.path("role").asString()))results.add(JSON.readTree(message.path("content").asString()).path("status").asString());
        assertEquals(List.of("QUERY_REQUIRES_USER_TEXT","QUERY_REQUIRES_USER_TEXT","QUERY_REQUIRES_USER_TEXT","QUERY_REQUIRES_USER_TEXT","RETRIEVAL_UNAVAILABLE"),results);
        assertEquals(before,drafts());assertNull(state(id));
    }
    @Test void singleFieldsPrepareOnceWithExactLiteralsAndNoImplicitConfirmation()throws Exception{
        var browser=browser();String id=create(browser,order);long before=drafts();long tickets=jdbc.queryForObject("SELECT COUNT(*) FROM tickets",Long.class);
        assertEquals("NEEDS_INPUT",send(browser,id,"反复卡纸，请准备报修。").path("status").asString());
        assertEquals("extra",state(id).pendingField());
        send(browser,id,"进纸处卡住，屏幕没有错误码。已检查纸盘。");send(browser,id,"小王");assertEquals("小王",state(id).contact());
        String turn=UUID.randomUUID().toString();var completed=send(browser,id,turn,"电话13800000000",201);var reply=completed.path("assistantMessage").path("response");
        assertEquals("DRAFT_PREPARED",reply.path("status").asString());assertEquals("小王",reply.path("draft").path("contact").asString());
        assertEquals("进纸处卡住，屏幕没有错误码。已检查纸盘。",reply.path("draft").path("extra").asString());
        assertEquals("PREPARED",state(id).phase());assertEquals(before+1,drafts());assertEquals(tickets,jdbc.queryForObject("SELECT COUNT(*) FROM tickets",Long.class));
        var replay=send(browser,id,turn,"电话13800000000",200);assertTrue(replay.path("replayed").asBoolean());assertEquals(completed.path("assistantMessage"),replay.path("assistantMessage"));assertEquals(before+1,drafts());assertEquals(0,requests.size());
        assertEquals(409,browser.request("POST","/api/conversations/"+id+"/messages",Map.of("turnId",turn,"content","站内联系")).statusCode());
        assertEquals(before+1,drafts());assertEquals("PREPARED",state(id).phase());
    }
    @Test void labeledFieldsCanPrepareWithPunctuation()throws Exception{
        var browser=browser();String id=create(browser,order);
        var reply=send(browser,id,"故障描述：反复卡纸；现场补充信息：屏幕无提示，已检查。；联系人：小王；联系方式：站内联系。请准备草稿。");
        assertEquals("DRAFT_PREPARED",reply.path("status").asString());assertEquals(0,requests.size());
    }
    @ParameterizedTest @ValueSource(strings={"你帮我猜","小王，然后提交","联系人：小王；联系人：小李","不要填了",
        "打印时没有什么异常声音","字段校验错误","字段 INVALID_ARGUMENT","打印机卡纸该怎么处理","那具体怎么操作",
        "现场：屏幕正常；联系人：小王，然后提交","现场：屏幕正常；立刻建单","现场：屏幕正常；；联系人：小王",
        "现场：屏幕正常\n已检查纸盘","现场：屏幕正常；未知字段：小王","故障：卡纸；故障描述：进纸卡住"})
    void ambiguousRepliesNeverFillOrWrite(String value)throws Exception{
        var browser=browser();String id=create(browser,order);send(browser,id,"反复卡纸，请准备报修");long before=drafts();var original=state(id);clearWorkInvocations();
        assertEquals("NEEDS_INPUT",send(browser,id,value).path("status").asString());assertEquals(original,state(id));noLookupOrDraftWrite(before);
    }
    @ParameterizedTest @ValueSource(strings={"现场补充信息：屏幕显示字段校验错误","现场：屏幕正常，已检查。；  ","现场：屏幕正常\n联系人：小王\n"})
    void validLabelsWinBeforeBroadTopicsAndApplyAllFields(String value)throws Exception{
        var browser=browser();String id=create(browser,order);send(browser,id,"反复卡纸，请准备报修");var original=state(id);long before=drafts();clearWorkInvocations();
        assertEquals("NEEDS_INPUT",send(browser,id,value).path("status").asString());var current=state(id);
        assertEquals("COLLECTING",current.phase());assertEquals(original.expiresAt(),current.expiresAt());assertEquals(original.authorizedTurn(),current.authorizedTurn());
        assertNotNull(current.extra());assertEquals(original.symptom(),current.symptom());
        if(value.contains("字段校验错误"))assertEquals("屏幕显示字段校验错误",current.extra());
        if(value.contains("联系人：小王"))assertEquals("小王",current.contact());
        noLookupOrDraftWrite(before);
    }
    @ParameterizedTest @ValueSource(strings={"联系人：小王；联系人：小李，请准备报修","现场：屏幕正常；立刻建单，请准备报修","故障：卡纸；故障描述：进纸卡住；请准备报修"})
    void invalidInitialLabelsNeverFallBackToSymptom(String value)throws Exception{
        var browser=browser();String id=create(browser,order);long before=drafts();clearWorkInvocations();
        assertEquals("NEEDS_INPUT",send(browser,id,value).path("status").asString());assertNull(state(id));noLookupOrDraftWrite(before);
    }
    @ParameterizedTest @ValueSource(strings={"联系人：无","联系人：没有","联系人：不提供","联系方式：无","联系方式：没有","联系方式：不提供"})
    void labeledAndUnlabeledContactRefusalsUseSameGuard(String value)throws Exception{
        var browser=browser();String id=create(browser,order);long before=drafts();clearWorkInvocations();
        assertEquals("NEEDS_INPUT",send(browser,id,value+"；请准备报修").path("status").asString());assertNull(state(id));noLookupOrDraftWrite(before);
        send(browser,id,"反复卡纸，请准备报修");send(browser,id,"屏幕正常");
        if(value.startsWith("联系方式"))send(browser,id,"小王");
        var original=state(id);clearWorkInvocations();
        for(String input:List.of(value,value.substring(value.indexOf('：')+1))){
            assertEquals("NEEDS_INPUT",send(browser,id,input).path("status").asString());assertEquals(original,state(id));
        }
        noLookupOrDraftWrite(before);
    }
    @ParameterizedTest @ValueSource(strings={"123","明确的站内联系"})
    void contactMethodRemainsFreeText(String value)throws Exception{
        var browser=browser();String id=create(browser,order);send(browser,id,"故障：卡纸；现场：屏幕正常；联系人：小王；请准备报修");
        var reply=send(browser,id,value);assertEquals("DRAFT_PREPARED",reply.path("status").asString());assertEquals(value,reply.path("draft").path("contactMethod").asString());assertEquals(0,requests.size());
    }
    @Test void barePreparationAtNinthMinutePreservesAuthorizationAndReplaysWithoutWrite()throws Exception{
        var browser=browser();String id=create(browser,order);send(browser,id,"反复卡纸，请准备报修");
        // Advance the authorization's age inside this isolated test database without waiting nine minutes.
        jdbc.update("UPDATE messages SET created_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 9 MINUTE) WHERE conversation_id=? AND turn_id=? AND role='USER'",id,state(id).authorizedTurn());
        jdbc.update("UPDATE conversation_dialogue_states SET expires_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE conversation_id=?",id);
        var original=state(id);String turn=UUID.randomUUID().toString();long before=drafts(),messages=mapper.count(id,a);clearWorkInvocations();
        var repeated=send(browser,id,turn,"请准备报修",201);assertEquals("NEEDS_INPUT",repeated.path("assistantMessage").path("response").path("status").asString());
        assertEquals(original,state(id));assertEquals(messages+2,mapper.count(id,a));
        var replay=send(browser,id,turn,"请准备报修",200);assertTrue(replay.path("replayed").asBoolean());assertEquals(repeated.path("assistantMessage"),replay.path("assistantMessage"));
        assertEquals(original,state(id));assertEquals(messages+2,mapper.count(id,a));noLookupOrDraftWrite(before);
    }
    @ParameterizedTest @ValueSource(strings={"现场：屏幕正常；请准备报修","出纸口卡住，请准备报修","故障：卡纸；现场：已检查；联系人：小王；联系方式：123；请准备报修"})
    void repeatedPreparationWithNewContentNeverResetsOrMerges(String value)throws Exception{
        var browser=browser();String id=create(browser,order);send(browser,id,"反复卡纸，请准备报修");var original=state(id);long before=drafts();clearWorkInvocations();
        assertEquals("NEEDS_INPUT",send(browser,id,value).path("status").asString());assertEquals(original,state(id));noLookupOrDraftWrite(before);
    }
    @Test void pausedPreparationAndFieldsStayPausedWhileReadOnlyQuestionsRemainAvailable()throws Exception{
        var browser=browser();String id=create(browser,order);send(browser,id,"反复卡纸，请准备报修");send(browser,id,"暂停填写");var paused=state(id);long before=drafts();clearWorkInvocations();
        for(String value:List.of("小王","现场：屏幕正常","请准备报修")){
            assertEquals("NEEDS_INPUT",send(browser,id,value).path("status").asString());assertEquals(paused,state(id));
        }
        noLookupOrDraftWrite(before);
        call("searchKnowledge",Map.of("query","打印机卡纸该怎么处理"));response("{\"kind\":\"NO_EVIDENCE\"}");
        assertEquals("RETRIEVAL_UNAVAILABLE",send(browser,id,"打印机卡纸该怎么处理").path("status").asString());
        assertEquals("PAUSED",state(id).phase());assertEquals(paused.values(),state(id).values());assertEquals(paused.expiresAt(),state(id).expiresAt());assertEquals(before,drafts());assertEquals(2,requests.size());
        org.mockito.Mockito.verify(retrieval).searchOwnedForAssistant(a,order,"打印机卡纸该怎么处理");org.mockito.Mockito.verifyNoInteractions(draftService);
    }
    @Test void pauseResumeExpiryAndCancelDoNotRenewOrDelete()throws Exception{
        var browser=browser();String id=create(browser,order);send(browser,id,"反复卡纸，请准备报修");var expiry=state(id).expiresAt();
        send(browser,id,"这个晚点再说");assertEquals("PAUSED",state(id).phase());send(browser,id,"小王");assertNull(state(id).extra());
        send(browser,id,"继续填写报修");assertEquals(expiry,state(id).expiresAt());send(browser,id,"屏幕无错误码");assertEquals("contact",state(id).pendingField());
        jdbc.update("UPDATE conversation_dialogue_states SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE conversation_id=?",id);
        assertEquals("NEEDS_INPUT",send(browser,id,"小王").path("status").asString());assertEquals("EXPIRED",state(id).phase());assertNull(state(id).contact());
        send(browser,id,"请准备报修");assertEquals("COLLECTING",state(id).phase());assertNull(state(id).symptom());
        long before=drafts();send(browser,id,"取消填写报修");assertEquals("CANCELLED",state(id).phase());assertEquals(before,drafts());
        response("{\"kind\":\"WORKFLOW\",\"intent\":\"SERVICE_SCOPE\"}");send(browser,id,"小王");assertEquals("CANCELLED",state(id).phase());assertEquals(before,drafts());
    }
    @Test void ownershipDeviceAndOptimisticRevisionAreCheckedInSql()throws Exception{
        var browser=browser();String id=create(browser,order);send(browser,id,"反复卡纸，请准备报修");var state=state(id);
        assertNull(mapper.dialogueState(id,b,order));assertNull(mapper.dialogueState(id,a,otherOrder));
        var other=new Browser();other.login(true);assertEquals(404,other.request("GET","/api/conversations/"+id,null).statusCode());
        assertEquals(404,other.request("POST","/api/conversations/"+id+"/messages",Map.of("turnId",UUID.randomUUID().toString(),"content","小王")).statusCode());
        assertNull(mapper.dialogueState(create(browser,otherOrder),a,otherOrder));
        assertEquals(0,mapper.updateDialogueState(state.next("CANCELLED",state.values(),state.lastTurn()),state.revision()-1));assertEquals(state,state(id));
    }
    @Test void oldAssistantPromptDoesNotCreateNewAuthorization()throws Exception{
        var browser=browser();String id=create(browser,order);String turn=UUID.randomUUID().toString();
        mapper.insertMessage(id,a,turn,"USER","反复卡纸，请准备报修",null);
        mapper.insertMessage(id,a,turn,"ASSISTANT","请补充联系人",JSON.writeValueAsString(Map.of("mode","MODEL_API","status","NEEDS_INPUT","text","请补充联系人","citations",List.of())));
        call("prepareRepairDraft",Map.of("symptom","反复卡纸","extra","屏幕正常","contact","小王","contactMethod","123"));response("{\"kind\":\"WORKFLOW\"}");
        long before=drafts();assertEquals("TOOL_REJECTED",send(browser,id,"现场：屏幕正常；联系人：小王；联系方式：123").path("status").asString());assertNull(state(id));assertEquals(before,drafts());
    }
    @Test void failedDraftValidationKeepsRecoverableCollectedState()throws Exception{
        var browser=browser();String id=create(browser,order);long before=drafts();
        send(browser,id,"故障描述：卡纸；现场："+"字".repeat(2001)+"；联系人：小王；联系方式：123。请准备报修");
        assertEquals(before,drafts());assertEquals("COLLECTING",state(id).phase());
        var failed=state(id);assertTrue(failed.missing().isEmpty());clearWorkInvocations();
        assertEquals("NEEDS_INPUT",send(browser,id,"请准备报修").path("status").asString());assertEquals(failed,state(id));noLookupOrDraftWrite(before);
        var fixed=send(browser,id,"现场：已检查纸张");assertEquals("DRAFT_PREPARED",fixed.path("status").asString());assertEquals(before+1,drafts());
        org.mockito.Mockito.verify(draftService).create(org.mockito.ArgumentMatchers.eq(a),org.mockito.ArgumentMatchers.any(local.printer.ticket.DraftService.Input.class));assertEquals(0,requests.size());
    }
    @Test void newerWindowCancellationPreventsOlderStateFromPreparing()throws Exception{
        var browser=browser();String id=create(browser,order);send(browser,id,"反复卡纸，请准备报修");send(browser,id,"屏幕无提示");send(browser,id,"小王");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var block=new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation->{if(block.getAndSet(false)){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",java.time.LocalDateTime.class);}).when(mapper).dialogueNow();
        var pool=Executors.newSingleThreadExecutor();long before=drafts();
        try{
            var old=pool.submit(()->send(browser,id,"13800000000"));assertTrue(entered.await(10,TimeUnit.SECONDS));
            send(browser,id,"取消填写报修");release.countDown();
            assertEquals("DRAFT_CONFLICT",old.get(10,TimeUnit.SECONDS).path("status").asString());assertEquals("CANCELLED",state(id).phase());assertEquals(before,drafts());
        }finally{release.countDown();pool.shutdownNow();org.mockito.Mockito.reset(mapper);}
    }
    @Test void cancellingCollectionNeverDeletesAnExistingDraft()throws Exception{
        var browser=browser();String id=create(browser,order);
        var draft=send(browser,id,"故障：卡纸；现场：已检查；联系人：小王；联系方式：123。请准备草稿").path("draft");assertTrue(draft.path("id").asLong()>0);
        long before=drafts();send(browser,id,"取消填写报修");assertEquals(before,drafts());
        assertEquals(200,browser.request("GET","/api/repair-drafts/"+draft.path("id").asLong(),null).statusCode());
    }
}

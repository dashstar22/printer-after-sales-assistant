package local.printer.assistant;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import local.printer.config.DependencyProperties;
import local.printer.knowledge.KnowledgeRetrievalService;
import local.printer.seed.*;
import local.printer.ticket.DraftService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP, Session/CSRF, MySQL and transactions; all model output is a loopback protocol fixture. */
@EnabledIfEnvironmentVariable(named="RUN_COLLECTION_SEMANTICS_IT",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","AI_ENABLED=true","ASSISTANT_COLLECTION_SEMANTICS_ENABLED=true",
    "ASSISTANT_COLLECTION_SEMANTICS_MODEL=collection-protocol-fixture","ASSISTANT_OBSERVATION_SEMANTICS_ENABLED=false",
    "ASSISTANT_READ_RETRIES=0","ASSISTANT_MAX_MODEL_CALLS=4","ASSISTANT_REQUEST_TIMEOUT_MS=5000",
    "LOGIN_REQUESTS_PER_MINUTE=10000","AI_REQUESTS_PER_MINUTE=10000","AI_IP_REQUESTS_PER_MINUTE=10000",
    "QDRANT_URL=http://127.0.0.1:9","REDIS_ENABLED=false","KNOWLEDGE_HYBRID_ENABLED=false","KNOWLEDGE_JOBS_ENABLED=false",
    "KNOWLEDGE_MAINTENANCE_ENABLED=false","EMAIL_ENABLED=false","AUTOMATION_ENABLED=false","CONTROLLED_DISPATCH_REQUIRED=false",
    "LEGACY_LOGIN_MODE=DISABLED","SESSION_COOKIE_SECURE=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class CollectionSemanticsIT {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Queue<ModelReply> replies=new ConcurrentLinkedQueue<>();
    static final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    static final AtomicReference<Throwable> protocolFailure=new AtomicReference<>();
    static HttpServer model;
    static String expectedDatabase;
    record ModelReply(int status,Map<String,Object> message,String finishReason){}

    @DynamicPropertySource static void isolatedConfiguration(DynamicPropertyRegistry registry)throws Exception{
        // Validate before Spring/Flyway opens a connection. Never echo a supplied connection URL.
        String databaseUrl=System.getenv("DB_URL");
        try{
            if(databaseUrl==null||!databaseUrl.startsWith("jdbc:mysql://"))throw new IllegalArgumentException();
            URI database=URI.create(databaseUrl.substring("jdbc:".length()));
            if(!"mysql".equals(database.getScheme())||!"127.0.0.1".equals(database.getHost())
                ||database.getPort()<1||database.getPort()>65535||database.getUserInfo()!=null||database.getFragment()!=null
                ||!database.getPath().matches("/printer_dialogue_it_[A-Za-z0-9_]+"))throw new IllegalArgumentException();
            expectedDatabase=database.getPath().substring(1);
        }catch(RuntimeException invalid){throw new IllegalStateException("ISOLATED_DIALOGUE_TEST_DATABASE_REQUIRED");}
        registry.add("spring.datasource.url",()->databaseUrl);
        model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/v1/chat/completions",exchange->{
            try{
                assertEquals("Bearer loopback-fixture",exchange.getRequestHeaders().getFirst("Authorization"));
                JsonNode request=JSON.readTree(exchange.getRequestBody().readAllBytes());requests.add(request);
                assertEquals("collection-protocol-fixture",request.path("model").asString());
                ModelReply next=replies.poll();int status=next==null?503:next.status();
                byte[] body=JSON.writeValueAsBytes(status==200?Map.of("id","collection-fixture","object","chat.completion","model","collection-protocol-fixture",
                    "choices",List.of(Map.of("index",0,"message",next.message(),"finish_reason",next.finishReason()))):
                    Map.of("error",Map.of("message","local protocol fixture unavailable")));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);
                exchange.getResponseBody().write(body);
            }catch(Throwable failure){protocolFailure.compareAndSet(null,failure);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){/* Retain the first protocol failure. */}}
            finally{exchange.close();}
        });model.start();
        registry.add("app.dependencies.ai.enabled",()->true);
        registry.add("app.dependencies.ai.base-url",()->"http://127.0.0.1:"+model.getAddress().getPort()+"/v1");
        registry.add("app.dependencies.ai.api-key",()->"loopback-fixture");
        registry.add("app.dependencies.ai.chat-model",()->"collection-protocol-fixture");
        registry.add("app.dependencies.qdrant.base-url",()->"http://127.0.0.1:9");
        registry.add("app.redis.enabled",()->false);registry.add("app.knowledge.hybrid.enabled",()->false);
        registry.add("app.knowledge.jobs.enabled",()->false);registry.add("app.automation.enabled",()->false);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    @Autowired DependencyProperties dependencies;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ConversationMapper mapper;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean KnowledgeRetrievalService retrieval;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean DraftService draftService;
    long customer,otherCustomer,order;
    final SeedPasswords passwords=new SeedPasswords(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());

    @BeforeAll void seedOnlyTheFreshSchema(){
        var ai=dependencies.ai();assertTrue(ai.enabled(),"LOCAL_PROTOCOL_FIXTURE_REQUIRED");
        assertEquals(URI.create("http://127.0.0.1:"+model.getAddress().getPort()+"/v1"),ai.baseUrl(),"LOCAL_PROTOCOL_FIXTURE_REQUIRED");
        assertEquals("loopback-fixture",ai.apiKey(),"LOCAL_PROTOCOL_FIXTURE_REQUIRED");
        assertEquals("collection-protocol-fixture",ai.chatModel(),"LOCAL_PROTOCOL_FIXTURE_REQUIRED");
        assertEquals(expectedDatabase,jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"),"SCHEMA_SCOPED_TEST_USER_REQUIRED");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class),"FRESH_TEST_SCHEMA_REQUIRED");
        seed.seed(passwords);
        customer=jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_a'",Long.class);
        otherCustomer=jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_b'",Long.class);
        assertEquals(2,jdbc.update("UPDATE users SET email=CONCAT(username,'@example.invalid'),display_email=CONCAT(username,'@example.invalid'),email_verified_at=CURRENT_TIMESTAMP(6) WHERE id IN (?,?)",customer,otherCustomer));
        order=jdbc.queryForObject("SELECT id FROM orders WHERE customer_id=?",Long.class,customer);
    }
    @BeforeEach void resetProtocol(){requests.clear();replies.clear();protocolFailure.set(null);clearInvocations(mapper,retrieval,draftService);}
    @AfterEach void protocolWasConsumedAsPlanned(){
        assertNull(protocolFailure.get(),()->"Loopback fixture failure: "+protocolFailure.get());assertTrue(replies.isEmpty(),"UNCONSUMED_LOCAL_PROTOCOL_REPLY");
    }
    @AfterAll void stopProtocol(){if(model!=null)model.stop(0);}

    class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();String token;
        HttpResponse<String> request(String method,String path,Object input)throws Exception{
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
            if(token!=null)request.header("X-CSRF-TOKEN",token);
            boolean form=input instanceof String;request.header("Content-Type",form?"application/x-www-form-urlencoded":"application/json");
            return client.send(request.method(method,input==null?HttpRequest.BodyPublishers.noBody():
                HttpRequest.BodyPublishers.ofString(form?(String)input:JSON.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());
        }
        void login(boolean other)throws Exception{
            token=ok(request("GET","/api/auth/csrf",null),200).path("token").asString();
            String email=(other?"demo_customer_b":"demo_customer_a")+"@example.invalid";
            assertEquals(200,request("POST","/api/auth/login","username="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password="+
                URLEncoder.encode(other?passwords.customerB():passwords.customerA(),StandardCharsets.UTF_8)).statusCode());
            token=ok(request("GET","/api/auth/csrf",null),200).path("token").asString();
        }
    }
    Browser browser()throws Exception{var browser=new Browser();browser.login(false);return browser;}
    JsonNode ok(HttpResponse<String> response,int status){assertEquals(status,response.statusCode(),response.body());return JSON.readTree(response.body());}
    String create(Browser browser)throws Exception{return ok(browser.request("POST","/api/conversations",Map.of("orderId",order)),201).path("id").asString();}
    JsonNode send(Browser browser,String id,String turn,String input,int status)throws Exception{
        return ok(browser.request("POST","/api/conversations/"+id+"/messages",Map.of("turnId",turn,"content",input)),status);
    }
    JsonNode send(Browser browser,String id,String input)throws Exception{return send(browser,id,UUID.randomUUID().toString(),input,201).path("assistantMessage").path("response");}
    String collecting(Browser browser)throws Exception{
        String id=create(browser);assertEquals("NEEDS_INPUT",send(browser,id,"请准备报修").path("status").asString());
        assertEquals("symptom",state(id).pendingField());return id;
    }
    DialogueState state(String id){return mapper.dialogueState(id,customer,order);}
    long count(String table){
        if(!Set.of("repair_drafts","tickets","repair_confirmations","ticket_events").contains(table))throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);
    }
    void classification(String kind){response("{\"kind\":\""+kind+"\"}");}
    void response(String output){replies.add(new ModelReply(200,Map.of("role","assistant","content",output),"stop"));}
    void noBusinessWrite(long drafts,long tickets){
        assertEquals(drafts,count("repair_drafts"));assertEquals(tickets,count("tickets"));verifyNoInteractions(retrieval,draftService);
    }
    void oneClassification(String input){
        assertEquals(1,requests.size());JsonNode request=requests.get(0),messages=request.path("messages");
        assertEquals("CollectionIntent",request.path("response_format").path("json_schema").path("name").asString());
        assertEquals(3,messages.size());assertEquals("system",messages.get(0).path("role").asString());assertEquals("system",messages.get(1).path("role").asString());
        assertEquals("user",messages.get(2).path("role").asString());assertEquals(input,messages.get(2).path("content").asString());
        assertTrue(!request.has("tools")||request.path("tools").isEmpty());assertEquals(128,request.path("max_tokens").asInt());
        assertFalse(request.toString().contains("loopback-fixture"));
    }
    JsonNode checkpoint(String id,String turn){return JSON.readTree(mapper.turn(id,customer,turn).get(1).responseJson());}

    @Test void approvedLabeledFieldsApplyAtomicallyAndPersistTheCurrentUserLiteral()throws Exception{
        var browser=browser();String id=collecting(browser),turn=UUID.randomUUID().toString();var original=state(id);
        long drafts=count("repair_drafts"),tickets=count("tickets");String input="故障：出纸口卡住。；现场：已重新放纸仍卡住。";
        clearInvocations(mapper,retrieval,draftService);classification("FIELD_VALUE");
        JsonNode completed=send(browser,id,turn,"\u3000"+input+"\u00a0",201);var current=state(id);
        assertEquals(input,completed.path("userMessage").path("content").asString());assertEquals("出纸口卡住。",current.symptom());
        assertEquals("已重新放纸仍卡住。",current.extra());assertNull(current.contact());assertNull(current.contactMethod());assertEquals("contact",current.pendingField());
        assertEquals(original.authorizedTurn(),current.authorizedTurn());assertEquals(original.expiresAt(),current.expiresAt());assertEquals(original.revision()+1,current.revision());
        assertEquals("NEEDS_INPUT",checkpoint(id,turn).path("status").asString());
        assertEquals(List.of("USER","ASSISTANT"),mapper.turn(id,customer,turn).stream().map(ConversationMapper.Message::role).toList());
        verify(mapper,times(1)).updateDialogueState(eq(current),eq(original.revision()));oneClassification(input);noBusinessWrite(drafts,tickets);
    }
    @Test void c22OutOfScopeRejectsTheWholeLabeledMessageIncludingItsOtherwiseUsableContact()throws Exception{
        var browser=browser();String id=collecting(browser);var original=state(id);long drafts=count("repair_drafts"),tickets=count("tickets");
        String input="故障：我喜欢吃汉堡；联系人：小王";clearInvocations(mapper,retrieval,draftService);classification("OUT_OF_SCOPE");
        JsonNode reply=send(browser,id,input);assertEquals("ANSWERED",reply.path("status").asString());assertTrue(reply.path("text").asString().contains("没有填入"));
        assertEquals(original,state(id));assertNull(state(id).symptom());assertNull(state(id).contact());
        verify(mapper,never()).updateDialogueState(any(),anyLong());oneClassification(input);noBusinessWrite(drafts,tickets);
    }
    @Test void unclearDoesNotWriteAFieldOrPrepareADraft()throws Exception{
        var browser=browser();String id=collecting(browser);var original=state(id);long drafts=count("repair_drafts"),tickets=count("tickets");
        String input="换纸了，还要怎么做";clearInvocations(mapper,retrieval,draftService);classification("UNCLEAR");
        assertEquals("NEEDS_INPUT",send(browser,id,input).path("status").asString());assertEquals(original,state(id));
        verify(mapper,never()).updateDialogueState(any(),anyLong());oneClassification(input);noBusinessWrite(drafts,tickets);
    }
    @Test void providerFailureStopsWithoutRetryOrLiteralFallback()throws Exception{
        var browser=browser();String id=collecting(browser);var original=state(id);long drafts=count("repair_drafts"),tickets=count("tickets");
        String input="进纸口反复卡住";clearInvocations(mapper,retrieval,draftService);replies.add(new ModelReply(503,Map.of(),"stop"));
        assertEquals("NEEDS_INPUT",send(browser,id,input).path("status").asString());assertEquals(original,state(id));
        verify(mapper,never()).updateDialogueState(any(),anyLong());oneClassification(input);noBusinessWrite(drafts,tickets);
    }
    @Test void anInvalidContactMethodMakesNoModelCallAndCannotCompleteTheDraft()throws Exception{
        var browser=browser();String id=collecting(browser);classification("FIELD_VALUE");
        assertEquals("NEEDS_INPUT",send(browser,id,"故障：反复卡纸；现场：已检查纸盘；联系人：小王").path("status").asString());
        var original=state(id);assertEquals("contactMethod",original.pendingField());long drafts=count("repair_drafts"),tickets=count("tickets");
        requests.clear();clearInvocations(mapper,retrieval,draftService);
        assertEquals("NEEDS_INPUT",send(browser,id,"苹果").path("status").asString());assertEquals(original,state(id));assertTrue(requests.isEmpty());
        verify(mapper,never()).updateDialogueState(any(),anyLong());noBusinessWrite(drafts,tickets);
    }
    @Test void collectingAllFieldsPreparesOnlyADraftAndRequiresAnExplicitIdempotentPageOperation()throws Exception{
        var browser=browser();String id=collecting(browser);long drafts=count("repair_drafts"),tickets=count("tickets"),confirmations=count("repair_confirmations"),events=count("ticket_events");
        String first="故障：反复卡纸；现场：已检查纸盘；联系人：小王";classification("FIELD_VALUE");
        assertEquals("NEEDS_INPUT",send(browser,id,first).path("status").asString());assertEquals("contactMethod",state(id).pendingField());
        String turn=UUID.randomUUID().toString(),input="13800000000";requests.clear();clearInvocations(mapper,retrieval,draftService);classification("FIELD_VALUE");
        JsonNode completed=send(browser,id,turn,input,201),reply=completed.path("assistantMessage").path("response"),draft=reply.path("draft");
        assertEquals("DRAFT_PREPARED",reply.path("status").asString());assertTrue(reply.path("operation").isNull());
        long draftId=draft.path("id").asLong(),version=draft.path("version").asLong();assertTrue(draftId>0);assertEquals("PREPARED",state(id).phase());
        assertEquals(input,jdbc.queryForObject("SELECT contact_method FROM repair_drafts WHERE id=? AND customer_id=?",String.class,draftId,customer));
        assertEquals(drafts+1,count("repair_drafts"));assertEquals(tickets,count("tickets"));assertEquals(confirmations,count("repair_confirmations"));
        JsonNode replay=send(browser,id,turn,input,200);assertTrue(replay.path("replayed").asBoolean());assertEquals(completed.path("assistantMessage"),replay.path("assistantMessage"));
        assertEquals(409,browser.request("POST","/api/conversations/"+id+"/messages",Map.of("turnId",turn,"content","13900000000")).statusCode());
        verify(draftService,times(1)).create(eq(customer),any(DraftService.Input.class));oneClassification(input);verifyNoInteractions(retrieval);
        assertEquals(400,browser.request("POST","/api/repair-drafts/"+draftId+"/confirmations",Map.of("confirmed",false,"expectedVersion",version)).statusCode());
        assertEquals(404,browser.request("POST","/api/ticket-operations/"+UUID.randomUUID()+"/execute",Map.of("confirmationId",UUID.randomUUID().toString(),"draftId",draftId,"expectedVersion",version)).statusCode());
        assertEquals(tickets,count("tickets"));assertEquals(confirmations,count("repair_confirmations"));
        JsonNode confirmation=ok(browser.request("POST","/api/repair-drafts/"+draftId+"/confirmations",Map.of("confirmed",true,"expectedVersion",version)),201);
        String operationId=confirmation.path("operationId").asString(),confirmationId=confirmation.path("confirmationId").asString();
        var execute=Map.of("confirmationId",confirmationId,"draftId",draftId,"expectedVersion",version);
        JsonNode operation=ok(browser.request("POST","/api/ticket-operations/"+operationId+"/execute",execute),201);
        assertEquals("SUCCEEDED",operation.path("status").asString());long ticket=operation.path("ticket").path("id").asLong();assertTrue(ticket>0);
        JsonNode repeated=ok(browser.request("POST","/api/ticket-operations/"+operationId+"/execute",execute),200);
        assertEquals(ticket,repeated.path("ticket").path("id").asLong());assertEquals(tickets+1,count("tickets"));assertEquals(events+1,count("ticket_events"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE draft_id=? AND customer_id=?",Integer.class,draftId,customer));
        assertEquals(ticket,jdbc.queryForObject("SELECT ticket_id FROM repair_confirmations WHERE id=?",Long.class,confirmationId));
        assertEquals(1,requests.size(),"Page confirmation and execute must not call a model");
    }
    @Test void knowledgeClassificationUsesTheExistingReadOnlySearchWithoutFillingThePendingField()throws Exception{
        var browser=browser();String id=collecting(browser);var original=state(id);long drafts=count("repair_drafts"),tickets=count("tickets");
        String input="卡纸该怎么处理";classification("KNOWLEDGE_QUESTION");
        replies.add(new ModelReply(200,Map.of("role","assistant","tool_calls",List.of(Map.of("id","bound-search","type","function",
            "function",Map.of("name","searchKnowledge","arguments",JSON.writeValueAsString(Map.of("query",input)))))),"tool_calls"));
        response("{\"kind\":\"NO_EVIDENCE\"}");
        // Only this read dependency is a fixture; HTTP, ownership, dialogue persistence and transactions remain real.
        doReturn(new KnowledgeRetrievalService.Result("NO_EVIDENCE","LOCAL_PROTOCOL_FIXTURE",0,0,0,false,0,List.of(),Map.of()))
            .when(retrieval).searchOwnedForAssistant(customer,order,input);
        clearInvocations(mapper,retrieval,draftService);
        assertEquals("NO_EVIDENCE",send(browser,id,input).path("status").asString());assertEquals(original,state(id));
        assertEquals(3,requests.size());assertEquals("required",requests.get(1).path("tool_choice").asString());
        assertEquals(1,requests.get(1).path("tools").size());assertEquals("searchKnowledge",requests.get(1).path("tools").get(0).path("function").path("name").asString());
        verify(retrieval,times(1)).searchOwnedForAssistant(customer,order,input);verifyNoMoreInteractions(retrieval);verifyNoInteractions(draftService);
        assertEquals(drafts,count("repair_drafts"));assertEquals(tickets,count("tickets"));verify(mapper,never()).updateDialogueState(any(),anyLong());
    }
    @Test void foreignSessionAndMissingCsrfCannotReachClassificationOrChangeState()throws Exception{
        var browser=browser();String id=collecting(browser);var original=state(id);var foreign=new Browser();foreign.login(true);
        long drafts=count("repair_drafts"),tickets=count("tickets");clearInvocations(mapper,retrieval,draftService);
        assertEquals(404,foreign.request("POST","/api/conversations/"+id+"/messages",Map.of("turnId",UUID.randomUUID().toString(),"content","出纸口卡住")).statusCode());
        browser.token=null;assertEquals(403,browser.request("POST","/api/conversations/"+id+"/messages",Map.of("turnId",UUID.randomUUID().toString(),"content","出纸口卡住")).statusCode());
        assertEquals(original,state(id));assertTrue(requests.isEmpty());verify(mapper,never()).updateDialogueState(any(),anyLong());noBusinessWrite(drafts,tickets);
    }
}

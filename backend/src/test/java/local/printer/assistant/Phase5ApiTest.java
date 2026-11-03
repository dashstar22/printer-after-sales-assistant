package local.printer.assistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import local.printer.knowledge.KnowledgeRetrievalService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.*;

/** Real HTTP/auth/CSRF/MySQL; Chat and retrieval are explicit controlled fixtures, not supplier evidence. */
@EnabledIfEnvironmentVariable(named="PHASE5_DB_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"AI_ENABLED=true","ASSISTANT_READ_RETRIES=0","REDIS_ENABLED=true","AI_REQUESTS_PER_MINUTE=500","AI_IP_REQUESTS_PER_MINUTE=1000","LOGIN_REQUESTS_PER_MINUTE=500","EMAIL_ENABLED=true","EMAIL_SITE_ORIGIN=https://phase5.invalid","EMAIL_FROM=service@phase5.invalid","EMAIL_SMTP_HOST=127.0.0.1","EMAIL_SMTP_PORT=9","EMAIL_CAPTURE_ONLY=true","EMAIL_SMTP_STARTTLS=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase5ApiTest {
    static final ObjectMapper JSON=new ObjectMapper();static HttpServer supplier;
    static final Queue<Map<String,Object>> replies=new ConcurrentLinkedQueue<>();static final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception{
        r.add("spring.datasource.url",()->System.getenv("PHASE5_DB_URL"));r.add("spring.datasource.username",()->System.getenv("PHASE4_DB_USER"));r.add("spring.datasource.password",()->System.getenv("PHASE4_DB_PASSWORD"));
        r.add("REDIS_NAMESPACE",()->"p5-api-"+System.getenv("PHASE5_DB_URL"));r.add("spring.data.redis.port",()->System.getenv("PHASE5_REDIS_PORT"));
        supplier=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);supplier.createContext("/v1/chat/completions",x->{
            requests.add(JSON.readTree(x.getRequestBody().readAllBytes()));var next=replies.poll();byte[] data=JSON.writeValueAsBytes(next==null?Map.of():Map.of("id","fixture","object","chat.completion","model","fixture","choices",List.of(Map.of("index",0,"message",next,"finish_reason",next.containsKey("tool_calls")?"tool_calls":"stop"))));
            x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(next==null?503:200,data.length);x.getResponseBody().write(data);x.close();});supplier.start();
        r.add("app.dependencies.ai.base-url",()->"http://127.0.0.1:"+supplier.getAddress().getPort()+"/v1");r.add("app.dependencies.ai.api-key",()->"local-fixture");r.add("app.dependencies.ai.chat-model",()->"fixture");
    }
    @LocalServerPort int port;@Autowired JdbcTemplate db;@Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @MockitoSpyBean KnowledgeRetrievalService retrieval;
    final String password="fixture-"+UUID.randomUUID();long a,b,order;volatile String query;
    @BeforeAll void setup(){
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_p5_"));
        var encoder=org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder();
        for(String name:List.of("a","b","c","staff","admin"))db.update("INSERT INTO users(username,password_hash,role,email,display_email,email_verified_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP(6))","p5-"+name,encoder.encode(password),name.equals("admin")?"ADMIN":name.equals("staff")?"STAFF":"CUSTOMER",name+"@phase5.invalid",name+"@phase5.invalid");
        a=id("a");b=id("b");db.update("INSERT INTO device_models(model_code,brand,is_demo) VALUES('P5-100','Fixture',TRUE)");
        db.update("INSERT INTO orders(order_no,customer_id,model_id,serial_no,purchase_date,is_demo) SELECT 'P5-ORDER',?,id,'P5-SERIAL','2026-01-01',TRUE FROM device_models WHERE model_code='P5-100'",a);
        order=db.queryForObject("SELECT id FROM orders WHERE customer_id=?",Long.class,a);
    }
    long id(String name){return db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,"p5-"+name);}
    @AfterAll void close(){supplier.stop(0);}
    @BeforeEach void resetFixture(){replies.clear();requests.clear();query=null;doAnswer(invocation->{query=invocation.getArgument(2);return result("HIT");}).when(retrieval).searchOwnedForAssistant(eq(a),eq(order),anyString());}
    KnowledgeRetrievalService.Result result(String status){return new KnowledgeRetrievalService.Result(status,"FIXTURE",1,0,1,false,0,List.of(new EvidenceAnswerPolicyTest().source("actual","警告：先断电并等待冷却。\n打开外盖。")),Map.of("configuration","hybrid-rerank-v1","vector","OK","bm25","OK","rerank",status.equals("EXCERPT_ONLY")?"UNAVAILABLE":"OK"));}
    class Browser{
        HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String token;
        HttpResponse<String> req(String method,String path,Object input)throws Exception{
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(token!=null)builder.header("X-CSRF-TOKEN",token);
            builder.header("Content-Type",input instanceof String?"application/x-www-form-urlencoded":"application/json");
            return client.send(builder.method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(input instanceof String?(String)input:JSON.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());
        }
        void csrf()throws Exception{token=JSON.readTree(req("GET","/api/auth/csrf",null).body()).path("token").asString();}
    }
    Browser login(String name)throws Exception{var b=new Browser();b.csrf();check(b.req("POST","/api/auth/login","username="+URLEncoder.encode(name+"@phase5.invalid",StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8)),200);b.csrf();return b;}
    JsonNode check(HttpResponse<String> response,int status){assertEquals(status,response.statusCode(),response.body());return JSON.readTree(response.body());}
    String create(Browser b)throws Exception{return check(b.req("POST","/api/conversations",Map.of("orderId",order)),201).path("id").asString();}
    JsonNode send(Browser b,String id,String turn,String text,int expected)throws Exception{return check(b.req("POST","/api/conversations/"+id+"/messages",Map.of("turnId",turn,"content",text)),expected);}
    void knowledge(String text){replies.add(Map.of("role","assistant","tool_calls",List.of(Map.of("id","fixture-tool","type","function","function",Map.of("name","searchKnowledge","arguments",JSON.writeValueAsString(Map.of("query",text)))))));replies.add(Map.of("role","assistant","content",JSON.writeValueAsString(Map.of("kind","KNOWLEDGE","points",List.of(Map.of("text","打开外盖。","sourceIds",List.of("actual")))))));}
    @Test void completionPersistsOriginalQueryReasonAndDoesNotPrepareDraft()throws Exception{
        var b=login("a");String id=create(b);send(b,id,UUID.randomUUID().toString(),"反复卡纸，请准备报修",201);
        knowledge("喷头怎么清理");send(b,id,UUID.randomUUID().toString(),"喷头怎么清理",201);int before=requests.size();
        var originalState=db.queryForMap("SELECT symptom,extra,contact,contact_method,authorized_turn,expires_at FROM conversation_dialogue_states WHERE conversation_id=?",id);
        knowledge("那具体怎么操作");var response=send(b,id,UUID.randomUUID().toString(),"那具体怎么操作",201);var r=response.path("assistantMessage").path("response");
        assertEquals("喷头怎么清理；那具体怎么操作",query);assertEquals(2,requests.size()-before);assertEquals("PREVIOUS_KNOWLEDGE_TURN",r.path("query").path("reason").asString());assertEquals(0,r.path("query").path("rewriteCalls").asInt());
        assertEquals("那具体怎么操作",response.path("userMessage").path("content").asString());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM repair_drafts",Integer.class));
        assertEquals(originalState,db.queryForMap("SELECT symptom,extra,contact,contact_method,authorized_turn,expires_at FROM conversation_dialogue_states WHERE conversation_id=?",id));
        int calls=requests.size();var unclear=send(b,create(b),UUID.randomUUID().toString(),"那怎么操作",201);assertEquals("NEEDS_INPUT",unclear.path("assistantMessage").path("response").path("status").asString());assertEquals(calls,requests.size());
    }
    @Test void conciseAndDegradedRepliesSurviveReplayAndHistoryWithSnapshot()throws Exception{
        var b=login("a");String id=create(b),turn=UUID.randomUUID().toString();knowledge("卡纸怎么处理");var response=send(b,id,turn,"卡纸怎么处理",201);var r=response.path("assistantMessage").path("response");
        assertEquals("ANSWERED",r.path("status").asString());assertEquals(1,r.path("points").size());assertTrue(r.path("text").asString().contains("等待冷却"));assertEquals("HUMAN_REVIEW_REQUIRED",r.path("retrieval").path("supportVerification").asString());
        assertEquals(response.path("assistantMessage"),send(b,id,turn,"卡纸怎么处理",200).path("assistantMessage"));
        assertTrue(check(b.req("GET","/api/conversations/"+id,null),200).toString().contains("actual"));
        doReturn(result("EXCERPT_ONLY")).when(retrieval).searchOwnedForAssistant(eq(a),eq(order),anyString());knowledge("卡纸怎么处理");var degraded=send(b,id,UUID.randomUUID().toString(),"卡纸怎么处理",201).path("assistantMessage").path("response");
        assertEquals("EXCERPT_ONLY",degraded.path("status").asString());assertEquals(1,degraded.path("citations").size());assertEquals("UNAVAILABLE",degraded.path("retrieval").path("rerank").asString());
    }
    @Test void feedbackIsOwnedIdempotentUpdatableAndAdministratorOnly()throws Exception{
        var a=login("a");String id=create(a);knowledge("卡纸怎么处理");var turn=send(a,id,UUID.randomUUID().toString(),"卡纸怎么处理",201);long message=turn.path("assistantMessage").path("id").asLong();String path="/api/conversations/"+id+"/messages/"+message+"/feedback";
        check(a.req("GET",path,null),200);var input=Map.of("rating","UNRESOLVED","reason","缺少说明");var first=check(a.req("PUT",path,input),200);assertEquals(first,check(a.req("PUT",path,input),200));
        check(a.req("PUT",path,Map.of("rating","HELPFUL")),200);assertEquals("HELPFUL",check(a.req("GET",path,null),200).path("rating").asString());check(a.req("PUT",path,input),200);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM answer_feedback WHERE message_id=?",Integer.class,message));
        var pool=Executors.newFixedThreadPool(4);
        try{var jobs=new ArrayList<Callable<Integer>>();for(int i=0;i<4;i++)jobs.add(()->a.req("PUT",path,input).statusCode());for(var future:pool.invokeAll(jobs))assertEquals(200,future.get());}finally{pool.shutdownNow();}
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM answer_feedback WHERE message_id=?",Integer.class,message));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_documents",Integer.class));
        check(login("b").req("PUT",path,input),404);check(login("staff").req("PUT",path,input),403);
        check(a.req("PUT",path,Map.of("rating","HELPFUL","customerId",id("b"))),400);
        check(a.req("PUT","/api/conversations/"+id+"/messages/"+turn.path("userMessage").path("id").asLong()+"/feedback",input),404);
        check(a.req("GET","/api/admin/knowledge-gaps",null),403);check(login("staff").req("GET","/api/admin/knowledge-gaps",null),403);
        var gaps=check(login("admin").req("GET","/api/admin/knowledge-gaps",null),200);assertTrue(gaps.toString().contains("P5-100"));assertTrue(gaps.toString().contains("缺少说明"));
        a.token=null;check(a.req("PUT",path,input),403);
    }
    @Test void emailCooldownRemainsUniformAndSqlAuthoritative()throws Exception{
        var b=new Browser();b.csrf();var first=check(b.req("POST","/api/auth/password-reset/request",Map.of("email","a@phase5.invalid")),202);
        assertEquals(first,check(b.req("POST","/api/auth/password-reset/request",Map.of("email","missing@phase5.invalid")),202));
        assertEquals(first,check(b.req("POST","/api/auth/password-reset/request",Map.of("email","a@phase5.invalid")),202));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM email_sends WHERE purpose='RESET'",Integer.class));
        for(int i=0;i<17;i++)assertEquals(first,check(b.req("POST","/api/auth/password-reset/request",Map.of("email","missing@phase5.invalid")),202));
        var blocked=b.req("POST","/api/auth/password-reset/request",Map.of("email","missing@phase5.invalid"));check(blocked,429);assertTrue(blocked.headers().firstValue("Retry-After").isPresent());
    }
    @Test void capacityQuestionReachesBoundRetrievalWithOriginalNumericValue()throws Exception{
        var browser=login("a");String conversation=create(browser),question="设备压缩文件恰好8388608字节，容量按哪个大小判断？";
        knowledge(question);var response=send(browser,conversation,UUID.randomUUID().toString(),question,201).path("assistantMessage").path("response");
        assertEquals(question,query);assertEquals("ANSWERED",response.path("status").asString());assertEquals("HIT",response.path("retrieval").path("status").asString());
        assertEquals(2,requests.size());
    }
    @Test void actualDeviceCountUsesLocalMissingInputAndReplaysWithoutModelOrWrites()throws Exception{
        var browser=login("a");String conversation=create(browser),turn=UUID.randomUUID().toString(),question="这台设备昨天实际完成多少个打印作业？";
        int before=db.queryForObject("SELECT COUNT(*) FROM repair_drafts",Integer.class);
        var response=send(browser,conversation,turn,question,201);var reply=response.path("assistantMessage").path("response");
        assertEquals("NEEDS_INPUT",reply.path("status").asString());assertEquals("SERVICE_GUIDANCE",reply.path("mode").asString());
        assertEquals("NOT_RUN",reply.path("retrieval").path("status").asString());assertTrue(reply.path("text").asString().contains("没有接入"));
        assertTrue(reply.path("citations").isEmpty());assertNull(query);assertTrue(requests.isEmpty());
        assertEquals(response.path("assistantMessage"),send(browser,conversation,turn,question,200).path("assistantMessage"));
        assertTrue(requests.isEmpty());assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM repair_drafts",Integer.class));
    }
    @Test void aiOverFrequencyHasRetryAfterWhileOrdinaryQueriesAndHealthContinue()throws Exception{
        var browser=login("c");long customer=id("c");db.update("INSERT INTO orders(order_no,customer_id,model_id,serial_no,purchase_date,is_demo) SELECT 'P5-C',?,model_id,'P5-C-SERIAL',purchase_date,TRUE FROM orders WHERE id=?",customer,order);
        long selected=db.queryForObject("SELECT id FROM orders WHERE customer_id=?",Long.class,customer);
        String conversation=check(browser.req("POST","/api/conversations",Map.of("orderId",selected)),201).path("id").asString();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("phase5-limit",Map.of("AI_REQUESTS_PER_MINUTE",2)));
        try{send(browser,conversation,UUID.randomUUID().toString(),"你好",201);send(browser,conversation,UUID.randomUUID().toString(),"你好",201);
            var denied=browser.req("POST","/api/conversations/"+conversation+"/messages",Map.of("turnId",UUID.randomUUID().toString(),"content","你好"));check(denied,429);assertEquals("60",denied.headers().firstValue("Retry-After").orElseThrow());
            check(browser.req("GET","/api/orders",null),200);check(browser.req("GET","/actuator/health",null),200);
            environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("phase5-login-limit",Map.of("LOGIN_REQUESTS_PER_MINUTE",1)));
            try{var anonymous=new Browser();anonymous.csrf();var login=anonymous.req("POST","/api/auth/login","username=missing%40phase5.invalid&password=unused");check(login,429);assertEquals("60",login.headers().firstValue("Retry-After").orElseThrow());check(browser.req("GET","/api/orders",null),200);}
            finally{environment.getPropertySources().remove("phase5-login-limit");}
        }finally{environment.getPropertySources().remove("phase5-limit");}
    }

}

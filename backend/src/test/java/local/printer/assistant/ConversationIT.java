package local.printer.assistant;

import java.net.*;
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

/** Real HTTP/Security/CSRF + isolated MySQL, with no test transaction or model Mock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"AI_ENABLED=false", "AI_API_KEY=", "AI_CHAT_MODEL=", "QDRANT_URL=http://127.0.0.1:9"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    @Autowired ConversationMapper mapper;
    final ObjectMapper json = new ObjectMapper();
    final SeedPasswords passwords = new SeedPasswords(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
    long a, b, orderA, orderB, secondOrderA;

    @BeforeAll void fixture() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_conversation_it_"));
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

    @Test void createBindsOnlyOwnOrderAndExposesWhitelistAndNotice() throws Exception {
        for (boolean asA:List.of(true,false)) {
            var browser=asA?customerA():customerB(); long order=asA?orderA:orderB;
            String id=create(browser,order);var d=detail(browser,id);
            assertEquals(Set.of("id","orderId","order","createdAt","notice","messages"),d.propertyNames());
            assertEquals(order,d.get("orderId").asLong());assertEquals(order,d.get("order").get("id").asLong());
            assertTrue(d.get("order").get("orderDemo").asBoolean());
            assertTrue(d.get("notice").asString().contains("更换打印机设备请新建咨询"));
            assertEquals(0,d.get("messages").get("total").asLong());
            assertEquals(asA?a:b,jdbc.queryForObject("SELECT customer_id FROM conversations WHERE id=?",Long.class,id));
            assertNotNull(Instant.parse(d.get("createdAt").asString()));
        }
    }
    @Test void foreignAndMissingOrdersHaveSame404AndDoNotCreate() throws Exception {
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM conversations",Long.class);
        for(boolean asA:List.of(true,false)) {
            var browser=asA?customerA():customerB();
            var foreign=error(browser.request("POST","/api/conversations",Map.of("orderId",asA?orderB:orderA)),404,"ORDER_NOT_FOUND");
            var missing=error(browser.request("POST","/api/conversations",Map.of("orderId",Long.MAX_VALUE)),404,"ORDER_NOT_FOUND");
            assertEquals(foreign.get("message"),missing.get("message"));
        }
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM conversations",Long.class));
    }
    @Test void crossCustomerReadAndWriteFailBothDirectionsAndMatchMissing() throws Exception {
        var ca=customerA();var cb=customerB();String ia=create(ca,orderA),ib=create(cb,orderB);
        submit(ca,ia,UUID.randomUUID().toString(),"仅A可见",201);submit(cb,ib,UUID.randomUUID().toString(),"仅B可见",201);
        for(boolean asA:List.of(true,false)) {
            var browser=asA?ca:cb;String foreign=asA?ib:ia,missing=UUID.randomUUID().toString();
            for(String method:List.of("GET","POST")) {
                String suffix=method.equals("POST")?"/messages":"";
                Object body=method.equals("POST")?input(UUID.randomUUID().toString(),"越权消息"):null;
                var f=error(browser.request(method,path(foreign)+suffix,body),404,"CONVERSATION_NOT_FOUND");
                var m=error(browser.request(method,path(missing)+suffix,body),404,"CONVERSATION_NOT_FOUND");
                assertEquals(f.get("message"),m.get("message"));
            }
        }
        assertEquals(2,count(ia));assertEquals(2,count(ib));
        assertEquals(0,mapper.count(ia,b));assertTrue(mapper.messages(ia,b,0,100).isEmpty());assertTrue(mapper.turn(ia,b,UUID.randomUUID().toString()).isEmpty());
        assertEquals(0,mapper.insertMessage(ia,b,UUID.randomUUID().toString(),"USER","forged",null));
    }
    @Test void anonymousExpiredLogoutAndCsrfAreEnforced() throws Exception {
        var browser=customerA();String id=create(browser,orderA);
        for(String p:List.of("/api/conversations",path(id),path(id)+"/messages")) {
            error(new Browser().request("GET",p,null),401,"UNAUTHENTICATED");
        }
        var anonymous=new Browser();anonymous.token=anonymous.csrf();
        error(anonymous.request("POST","/api/conversations",Map.of("orderId",orderA)),401,"UNAUTHENTICATED");
        error(anonymous.request("POST",path(id)+"/messages",input(UUID.randomUUID().toString(),"消息")),401,"UNAUTHENTICATED");
        for(String p:List.of("/api/conversations",path(id)+"/messages"))
            error(browser.raw("POST",p,"{}",null,"application/json"),403,"CSRF_INVALID");
        assertEquals(200,browser.request("POST","/api/auth/logout",Map.of()).statusCode());
        error(browser.request("GET",path(id),null),401,"UNAUTHENTICATED");
        assertEquals(0,count(id));
    }
    @Test void staffAndImplicitHeadCannotBypassRoleOrOwnership() throws Exception {
        var customer=customerA();String id=create(customer,orderA);var staff=login("demo_staff",passwords.staff());
        error(staff.request("GET",path(id),null),403,"FORBIDDEN");
        error(staff.request("POST","/api/conversations",Map.of("orderId",orderA)),403,"FORBIDDEN");
        error(staff.request("POST",path(id)+"/messages",input(UUID.randomUUID().toString(),"消息")),403,"FORBIDDEN");
        assertEquals(200,customer.request("HEAD",path(id),null).statusCode());
        assertEquals(404,customerB().request("HEAD",path(id),null).statusCode());
        assertEquals(403,staff.request("HEAD",path(id),null).statusCode());
        assertEquals(401,new Browser().request("HEAD",path(id),null).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"customerId","userId","role","orderId","response","citations"})
    void forgedFieldsCannotSetIdentityOrderOrAssistantResult(String field) throws Exception {
        var browser=customerA();String id=create(browser,orderA);
        var body=new LinkedHashMap<String,Object>(input(UUID.randomUUID().toString(),"正文"));body.put(field,b);
        error(browser.request("POST",path(id)+"/messages",body),400,"INVALID_ARGUMENT");
        if(!field.equals("orderId")) error(browser.request("POST","/api/conversations",Map.of("orderId",orderA,field,b)),400,"INVALID_ARGUMENT");
        var d=success(browser.request("GET",path(id)+"?userId="+b+"&customerId="+b+"&role=STAFF",null),200);
        assertEquals(orderA,d.get("orderId").asLong());assertEquals(0,count(id));
    }
    @Test void switchingDeviceStartsEmptyConversationAndKeepsOldContext() throws Exception {
        var browser=customerA();String old=create(browser,orderA);
        var turn=submit(browser,old,UUID.randomUUID().toString(),"第一台打印机卡纸",201);
        var oldState=detail(browser,old);String next=create(browser,secondOrderA);
        assertNotEquals(old,next);assertEquals(0,count(next));assertEquals(2,count(old));
        assertEquals(oldState,detail(browser,old));assertEquals(secondOrderA,detail(browser,next).get("orderId").asLong());
        assertEquals(turn.get("userMessage"),detail(browser,old).get("messages").get("items").get(0));
        assertNotEquals(old,create(browser,orderA)); // explicit new consultation even for the same printer
    }
    @Test void structuredAcknowledgementIsPersistedAndRestoredWithoutGeneratingAi() throws Exception {
        var browser=customerA();String id=create(browser,orderA);String turn=UUID.randomUUID().toString();
        var before=List.of("repair_drafts","repair_confirmations","tickets","ticket_events").stream().map(t->jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Long.class)).toList();
        var posted=submit(browser,id,turn,"\u3000卡纸 🖨\n已检查\u00a0",201);
        assertEquals(Set.of("conversationId","turnId","replayed","userMessage","assistantMessage"),posted.propertyNames());
        var user=posted.get("userMessage");var assistant=posted.get("assistantMessage");var reply=assistant.get("response");
        assertEquals(Set.of("id","turnId","role","content","response","createdAt"),user.propertyNames());
        assertEquals("卡纸 🖨\n已检查",user.get("content").asString());assertTrue(user.get("response").isNull());
        assertEquals("USER",user.get("role").asString());assertEquals("ASSISTANT",assistant.get("role").asString());
        assertEquals(Set.of("mode","status","text","citations","draft","operation"),reply.propertyNames());
        assertEquals("PERSISTENCE_ONLY",reply.get("mode").asString());assertEquals("ASSISTANT_NOT_CONNECTED",reply.get("status").asString());
        assertEquals(0,reply.get("citations").size());assertTrue(reply.get("draft").isNull());assertTrue(reply.get("operation").isNull());
        assertEquals(reply.get("text"),assistant.get("content"));
        var restored=detail(browser,id).get("messages").get("items");assertEquals(user,restored.get(0));assertEquals(assistant,restored.get(1));
        assertEquals(restored,detail(customerA(),id).get("messages").get("items")); // fresh authenticated client, no local cache
        assertEquals(json.readTree(jdbc.queryForObject("SELECT response_json FROM messages WHERE id=?",String.class,assistant.get("id").asLong())),reply);
        var after=List.of("repair_drafts","repair_confirmations","tickets","ticket_events").stream().map(t->jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Long.class)).toList();
        assertEquals(before,after);
    }
    @Test void lostResponseRetryReturnsOriginalPairAndChangedContentConflicts() throws Exception {
        var browser=customerA();String id=create(browser,orderA),turn=UUID.randomUUID().toString();
        assertEquals(201,browser.request("POST",path(id)+"/messages",input(turn,"原消息")).statusCode()); // deliberately discard body
        var restored=detail(browser,id).get("messages").get("items");var retry=submit(browser,id,turn,"原消息",200);
        assertTrue(retry.get("replayed").asBoolean());assertEquals(restored.get(0),retry.get("userMessage"));assertEquals(restored.get(1),retry.get("assistantMessage"));
        error(browser.request("POST",path(id)+"/messages",input(turn,"不同消息")),409,"CONVERSATION_CONFLICT");assertEquals(2,count(id));
    }
    @Test void concurrentSameTurnSavesExactlyOnePairAndDifferentTurnsStayPaired() throws Exception {
        var browser=customerA();String id=create(browser,orderA),turn=UUID.randomUUID().toString();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var gate=new CountDownLatch(1);
            Callable<HttpResponse<String>> send=()->{gate.await();return browser.request("POST",path(id)+"/messages",input(turn,"同时发送"));};
            var first=pool.submit(send);var second=pool.submit(send);gate.countDown();
            var results=List.of(first.get(25,TimeUnit.SECONDS),second.get(25,TimeUnit.SECONDS));
            assertEquals(Set.of(200,201),new HashSet<>(results.stream().map(HttpResponse::statusCode).toList()));
            assertEquals(json.readTree(results.get(0).body()).get("userMessage"),json.readTree(results.get(1).body()).get("userMessage"));assertEquals(2,count(id));
            var one=pool.submit(()->browser.request("POST",path(id)+"/messages",input(UUID.randomUUID().toString(),"下一轮一")));
            var two=pool.submit(()->browser.request("POST",path(id)+"/messages",input(UUID.randomUUID().toString(),"下一轮二")));
            assertEquals(201,one.get(25,TimeUnit.SECONDS).statusCode());assertEquals(201,two.get(25,TimeUnit.SECONDS).statusCode());
            var items=detail(browser,id).get("messages").get("items");assertEquals(6,items.size());
            for(int i=0;i<6;i+=2) {assertEquals(items.get(i).get("turnId"),items.get(i+1).get("turnId"));assertTrue(items.get(i).get("id").asLong()<items.get(i+1).get("id").asLong());}
        } finally { pool.shutdownNow(); }
    }
    @Test void secondMessageDatabaseFailureRollsBackEntireTurnAndAllowsOriginalRetry() throws Exception {
        var browser=customerA();String id=create(browser,orderA),turn=UUID.randomUUID().toString();
        // A scoped temporary CHECK needs only this isolated schema's DDL privilege; no global trigger/binlog setting.
        jdbc.execute("ALTER TABLE messages ADD CONSTRAINT ck_t025_fail_ack CHECK (conversation_id<>'"+id+"' OR role<>'ASSISTANT')");
        try {error(browser.request("POST",path(id)+"/messages",input(turn,"原消息")),503,"DATABASE_UNAVAILABLE");assertEquals(0,count(id));}
        finally {jdbc.execute("ALTER TABLE messages DROP CHECK ck_t025_fail_ack");}
        submit(browser,id,turn,"原消息",201);assertEquals(2,count(id));
    }
    @Test void paginationRecoversAllSubmittedMessagesInStableOrder() throws Exception {
        var browser=customerA();String id=create(browser,orderA);
        for(int i=0;i<11;i++) submit(browser,id,UUID.randomUUID().toString(),"消息"+i,201);
        var first=detail(browser,id).get("messages");assertEquals(20,first.get("items").size());assertEquals(22,first.get("total").asLong());assertEquals(2,first.get("totalPages").asLong());
        var second=success(browser.request("GET",path(id)+"?page=2",null),200).get("messages");assertEquals(2,second.get("items").size());
        assertTrue(first.get("items").get(19).get("id").asLong()<second.get("items").get(0).get("id").asLong());
        assertEquals(22,success(browser.request("GET",path(id)+"?size=100",null),200).get("messages").get("items").size());
        assertEquals(0,success(browser.request("GET",path(id)+"?page=2147483647",null),200).get("messages").get("items").size());
    }
    @ParameterizedTest @ValueSource(strings={"", " ", "\u3000\u00a0", "\u0000", "\u0001"})
    void invalidMessageTextDoesNotWrite(String content) throws Exception {
        var browser=customerA();String id=create(browser,orderA);
        error(browser.request("POST",path(id)+"/messages",input(UUID.randomUUID().toString(),content)),400,"INVALID_ARGUMENT");assertEquals(0,count(id));
    }
    @Test void unicodeLengthBoundarySurrogatesAndPlainHtmlRoundTrip() throws Exception {
        var browser=customerA();String id=create(browser,orderA);
        String boundary="🖨".repeat(4000);assertEquals(boundary,submit(browser,id,UUID.randomUUID().toString(),boundary,201).get("userMessage").get("content").asString());
        error(browser.request("POST",path(id)+"/messages",input(UUID.randomUUID().toString(),boundary+"a")),400,"INVALID_ARGUMENT");
        error(browser.raw("POST",path(id)+"/messages","{\"turnId\":\""+UUID.randomUUID()+"\",\"content\":\"\\ud800\"}",browser.token,"application/json"),400,"INVALID_ARGUMENT");
        String html="<script>alert('测试')</script>";assertEquals(html,submit(browser,id,UUID.randomUUID().toString(),html,201).get("userMessage").get("content").asString());
        // This is JSON preservation only; browser HTML rendering is T033.
    }
    @Test void malformedBodiesIdentifiersAndPagingUseUnifiedErrors() throws Exception {
        var browser=customerA();String id=create(browser,orderA);
        for(Object body:List.of(Map.of(),Map.of("orderId",0),Map.of("orderId",-1),Map.of("orderId","1"),Map.of("orderId",1.5),List.of(1)))
            error(browser.request("POST","/api/conversations",body),400,"INVALID_ARGUMENT");
        for(Object body:List.of(Map.of(),Map.of("turnId","bad","content","正文"),Map.of("turnId",UUID.randomUUID().toString(),"content",1),List.of(1)))
            error(browser.request("POST",path(id)+"/messages",body),400,"INVALID_ARGUMENT");
        for(String raw:List.of("null","{","{\"orderId\":9223372036854775808}")) error(browser.raw("POST","/api/conversations",raw,browser.token,"application/json"),400,"INVALID_ARGUMENT");
        error(browser.raw("POST",path(id)+"/messages","{}",browser.token,"text/plain"),415,"UNSUPPORTED_MEDIA_TYPE");
        for(String invalid:List.of("0","bad","00000000-0000-0000-0000-00000000000Z")) {
            error(browser.request("GET",path(invalid),null),400,"INVALID_ARGUMENT");
            error(browser.request("POST",path(invalid)+"/messages",input(UUID.randomUUID().toString(),"正文")),400,"INVALID_ARGUMENT");
        }
        for(String query:List.of("page=0","page=","page=-1","page=abc","page=2147483648","page=1&page=2","size=0","size=101","size=","size=1&size=2"))
            error(browser.request("GET",path(id)+"?"+query,null),400,"INVALID_ARGUMENT");
        assertEquals(0,count(id));
    }
    @Test void migrationConstraintsRejectOrphansRolesAndDuplicateTurn() throws Exception {
        assertEquals(List.of("1","2","3","4","5","6","7","8","9","10"),jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank",String.class));
        var browser=customerA();String id=create(browser,orderA),turn=UUID.randomUUID().toString();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO conversations(id,customer_id,order_id) VALUES(?,?,?)",UUID.randomUUID().toString(),a,Long.MAX_VALUE));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO messages(conversation_id,turn_id,role,content) VALUES(?,?,?,?)",UUID.randomUUID().toString(),turn,"USER","orphan"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->mapper.insertMessage(id,a,turn,"SYSTEM","bad",null));
        assertThrows(org.springframework.dao.DataAccessException.class,()->mapper.insertMessage(id,a,turn,"ASSISTANT","bad",null));
        submit(browser,id,turn,"正文",201);
        assertThrows(org.springframework.dao.DataAccessException.class,()->mapper.insertMessage(id,a,turn,"USER","duplicate",null));
        assertEquals(2,count(id));
    }
}

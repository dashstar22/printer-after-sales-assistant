package local.printer.ticket;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import local.printer.auth.SessionUser;
import local.printer.seed.DemoSeedService;
import local.printer.seed.SeedPasswords;
import local.printer.ticket.TicketRows.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL/HTTP/事务；隔离库夹具保留，无测试外层事务或Mock。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"AI_ENABLED=false","AI_API_KEY=","AI_CHAT_MODEL=","QDRANT_URL=http://127.0.0.1:9"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TicketHandlingIT {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class Configuration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        Clock testClock() { return Clock.fixed(Instant.parse("2026-10-27T09:00:00.123456Z"),ZoneOffset.UTC); }
        @org.springframework.context.annotation.Bean
        EventFault eventFault() { return new EventFault(); }
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TicketMapper mapper;
    @Autowired TicketHandlingService handling;
    @Autowired EventFault fault;
    @Autowired DemoSeedService seed;
    @Autowired PasswordEncoder encoder;
    final ObjectMapper json=new ObjectMapper();
    final SeedPasswords passwords=new SeedPasswords(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());
    final String usernameA="handling_a_"+UUID.randomUUID(),usernameB="handling_b_"+UUID.randomUUID(),usernameStaff="handling_s_"+UUID.randomUUID();
    final String secondStaff="handling_s2_"+UUID.randomUUID();
    long a,b,staff,staff2,orderA,orderB;
    @BeforeAll void isolatedMySqlAndIndependentAccounts() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_handling_it_"));
        assertEquals("8.4.13",jdbc.queryForObject("SELECT VERSION()",String.class));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"));
        seed.seed(passwords);
        for(var row:List.of(List.of(usernameA,passwords.customerA(),"CUSTOMER"),List.of(usernameB,passwords.customerB(),"CUSTOMER"),
            List.of(usernameStaff,passwords.staff(),"STAFF"),List.of(secondStaff,passwords.staff(),"STAFF")))
            jdbc.update("INSERT INTO users(username,password_hash,role) VALUES(?,?,?)",row.get(0),encoder.encode(row.get(1)),row.get(2));
        a=userId(usernameA);b=userId(usernameB);staff=userId(usernameStaff);staff2=userId(secondStaff);
        long model=jdbc.queryForObject("SELECT id FROM device_models WHERE model_code='HL-L2350DW'",Long.class);
        orderA=order(a,model);orderB=order(b,model);
    }
    long userId(String name) { return jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name); }
    long order(long customer,long model) {
        String no="HANDLING-"+UUID.randomUUID();
        jdbc.update("INSERT INTO orders(order_no,customer_id,model_id,serial_no,purchase_date,is_demo) VALUES(?,?,?,'DEMO-HANDLING','2026-10-27',true)",no,customer,model);
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_no=?",Long.class,no);
    }
    class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token;
        HttpResponse<String> raw(String method, String path, String body, String csrf, String contentType) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(15));
            if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            if (contentType != null) builder.header("Content-Type", contentType);
            return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        String csrf() throws Exception {
            var response = raw("GET", "/api/auth/csrf", null, null, null);
            assertEquals(200, response.statusCode());
            return json.readTree(response.body()).get("token").asString();
        }
        HttpResponse<String> request(String method, String path, Object body) throws Exception {
            return raw(method, path, body == null ? null : json.writeValueAsString(body), token, "application/json");
        }
    }
    Browser login(String name, String password) throws Exception {
        var browser = new Browser();
        var response = browser.raw("POST", "/api/auth/login", "username="+name+"&password="+URLEncoder.encode(password, StandardCharsets.UTF_8), browser.csrf(), "application/x-www-form-urlencoded");
        assertEquals(200, response.statusCode());
        browser.token = browser.csrf();
        return browser;
    }
    Browser customerA() throws Exception { return login(usernameA, passwords.customerA()); }
    Browser customerB() throws Exception { return login(usernameB, passwords.customerB()); }

    Browser staffOne() throws Exception {return login(usernameStaff,passwords.staff());}
    Browser staffTwo() throws Exception {return login(secondStaff,passwords.staff());}
    record Work(long ticket,long draft,String confirmation,String operation) {}
    Work work(Browser browser,long order) throws Exception {
        var d=browser.request("POST","/api/repair-drafts",Map.of("orderId",order,"symptom","反复卡纸 🖨","extra","已取纸\n仍报错","contact","模拟联系人","contactMethod","模拟方式"));
        assertEquals(201,d.statusCode(),d.body());long draft=json.readTree(d.body()).get("id").asLong();
        var c=browser.request("POST","/api/repair-drafts/"+draft+"/confirmations",Map.of("confirmed",true,"expectedVersion",0));
        assertEquals(201,c.statusCode(),c.body());var body=json.readTree(c.body());
        String confirmation=body.get("confirmationId").asString(),operation=body.get("operationId").asString();
        var ticket=browser.request("POST","/api/ticket-operations/"+operation+"/execute",Map.of("confirmationId",confirmation,"draftId",draft,"expectedVersion",0));
        assertEquals(201,ticket.statusCode(),ticket.body());return new Work(json.readTree(ticket.body()).get("ticket").get("id").asLong(),draft,confirmation,operation);
    }
    Work fresh() throws Exception {return work(customerA(),orderA);}
    String path(Work w,String action) {return "/api/tickets/"+w.ticket()+"/"+action;}
    Map<String,Object> input(String action,long version) {
        var body=new LinkedHashMap<String,Object>();body.put("expectedVersion",version);
        if(!action.equals("accept"))body.put("note","　 已检查进纸器\n等待复测 🖨　 ");return body;
    }
    JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());return json.readTree(response.body());
    }
    JsonNode error(HttpResponse<String> response,int status,String code) throws Exception {
        assertEquals(status,response.statusCode(),response.body());var body=json.readTree(response.body());
        assertEquals(Set.of("code","message","traceId"),body.propertyNames());assertEquals(code,body.get("code").asString());
        assertEquals(body.get("traceId").asString(),response.headers().firstValue("X-Trace-Id").orElseThrow());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertFalse(response.body().contains("SELECT"));return body;
    }
    JsonNode send(Browser client,Work w,String action,long version) throws Exception {return ok(client.request("POST",path(w,action),input(action,version)));}
    void accepted(Work w) throws Exception {send(staffOne(),w,"accept",0);}
    List<Object> frozen(Work w) {
        return List.of(mapper.draft(w.draft()),mapper.confirmation(w.confirmation()),
            jdbc.queryForMap("SELECT id,ticket_no,draft_id,customer_id,order_id,snapshot_symptom,snapshot_extra,snapshot_contact,snapshot_contact_method FROM tickets WHERE id=?",w.ticket()),
            mapper.events(w.ticket()).stream().filter(e->e.eventType().equals("CREATED")).toList());
    }
    List<Object> state(Work w) {return List.of(mapper.ticket(w.ticket()),mapper.events(w.ticket()),frozen(w));}
    void result(JsonNode result,Work w,String status,long version,long accepted,long actor,String type) {
        assertEquals(Set.of("id","status","version","acceptedBy","event"),result.propertyNames());
        assertEquals(w.ticket(),result.get("id").asLong());assertEquals(status,result.get("status").asString());assertEquals(version,result.get("version").asLong());assertEquals(accepted,result.get("acceptedBy").asLong());
        var event=result.get("event");assertEquals(Set.of("id","actorId","eventType","note","createdAt"),event.propertyNames());
        assertEquals(actor,event.get("actorId").asLong());assertEquals(type,event.get("eventType").asString());
        assertEquals("2026-10-27T09:00:00.123456Z",event.get("createdAt").asString());
        var stored=mapper.events(w.ticket()).stream().filter(e->e.id()==event.get("id").asLong()).findFirst().orElseThrow();
        assertEquals(actor,stored.actorId());assertEquals(type,stored.eventType());assertEquals(event.get("note").asString(),stored.note());
        assertEquals(LocalDateTime.parse("2026-10-27T09:00:00.123456"),stored.createdAt());
        assertEquals(status,mapper.ticket(w.ticket()).status());assertEquals(version,mapper.ticket(w.ticket()).version());
    }

    @Test void fullWorkflowWithAnotherStaffCollaborationAndCustomerRead() throws Exception {
        var w=fresh();var frozen=frozen(w);var first=staffOne();var other=staffTwo();
        var accept=send(first,w,"accept",0);result(accept,w,"IN_PROGRESS",1,staff,staff,"ACCEPTED");assertEquals("客服接单",accept.get("event").get("note").asString());
        var note=send(other,w,"notes",1);result(note,w,"IN_PROGRESS",2,staff,staff2,"NOTE");assertEquals("已检查进纸器\n等待复测 🖨",note.get("event").get("note").asString());
        result(send(other,w,"close",2),w,"CLOSED",3,staff,staff2,"CLOSED");assertEquals(frozen,frozen(w));assertEquals(4,mapper.events(w.ticket()).size());
        var owner=customerA();var detail=ok(owner.request("GET","/api/tickets/"+w.ticket(),null));assertEquals("CLOSED",detail.get("status").asString());assertEquals(4,detail.get("events").size());
        long previous=0;for(var event:detail.get("events")){long id=event.get("id").asLong();assertTrue(id>previous);previous=id;}
        var page=ok(owner.request("GET","/api/tickets?status=CLOSED&keyword="+mapper.ticket(w.ticket()).ticketNo(),null));assertEquals(w.ticket(),page.get("items").get(0).get("id").asLong());
        error(customerB().request("GET","/api/tickets/"+w.ticket(),null),404,"TICKET_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"accept","notes","close"})
    void customerCannotForgeStaffOrActor(String action) throws Exception {
        var w=fresh();var before=state(w);var client=customerA();var body=input(action,0);body.put("role","STAFF");body.put("actorId",staff);body.put("acceptedBy",staff);
        error(client.request("POST",path(w,action),body),403,"FORBIDDEN");assertEquals(before,state(w));
    }
    @ParameterizedTest @ValueSource(strings={"accept","notes","close"})
    void anonymousAndCsrfOrdering(String action) throws Exception {
        var w=fresh();var before=state(w);var client=new Browser();
        error(client.request("POST",path(w,action),input(action,0)),403,"CSRF_INVALID");
        client.token=client.csrf();error(client.request("POST",path(w,action),input(action,0)),401,"UNAUTHENTICATED");assertEquals(before,state(w));
    }
    @ParameterizedTest @ValueSource(strings={"accept","notes","close"})
    void staffMissingOrWrongCsrfDoesNotWrite(String action) throws Exception {
        var w=fresh();var before=state(w);var client=staffOne();String body=json.writeValueAsString(input(action,0));
        error(client.raw("POST",path(w,action),body,null,"application/json"),403,"CSRF_INVALID");
        error(client.raw("POST",path(w,action),body,"invalid","application/json"),403,"CSRF_INVALID");assertEquals(before,state(w));
    }
    @ParameterizedTest @ValueSource(strings={"actorId","acceptedBy","customerId","role","status","version"})
    void staffCannotSupplyTrustedOrUnknownFields(String field) throws Exception {
        var w=fresh();var before=state(w);var body=input("accept",0);body.put(field,"forged");
        error(staffOne().request("POST",path(w,"accept"),body),400,"INVALID_ARGUMENT");assertEquals(before,state(w));
    }
    @ParameterizedTest @ValueSource(strings={"-1","9223372036854775808","0.0","true","null","\"0\"","[]","{}"})
    void invalidVersionTypesAndRangeRejected(String value) throws Exception {
        var w=fresh();var before=state(w);var client=staffOne();
        error(client.raw("POST",path(w,"accept"),"{\"expectedVersion\":"+value+"}",client.token,"application/json"),400,"INVALID_ARGUMENT");assertEquals(before,state(w));
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{","{\"expectedVersion\":0,\"expectedVersion\":0}","{\"expectedVersion\":0} {}"})
    void invalidRootMissingDuplicateOrTrailingJsonRejected(String body) throws Exception {
        var w=fresh();var before=state(w);var client=staffOne();error(client.raw("POST",path(w,"accept"),body,client.token,"application/json"),400,"INVALID_ARGUMENT");assertEquals(before,state(w));
    }
    @ParameterizedTest @ValueSource(strings={"notes","close"})
    void invalidNoteVariantsAndEmptyBodyDoNotWrite(String action) throws Exception {
        var w=fresh();accepted(w);var before=state(w);var client=staffOne();
        for(String value:List.of("null","\"\"","\"　 \\t\\n　\"","123","true"))
            error(client.raw("POST",path(w,action),"{\"expectedVersion\":1,\"note\":"+value+"}",client.token,"application/json"),400,"INVALID_ARGUMENT");
        error(client.request("POST",path(w,action),Map.of("expectedVersion",1)),400,"INVALID_ARGUMENT");
        error(client.request("POST",path(w,action),Map.of("expectedVersion",1,"note","🖨".repeat(2001))),400,"INVALID_ARGUMENT");
        error(client.raw("POST",path(w,action),"{\"expectedVersion\":1,\"note\":\"a\",\"note\":\"b\"}",client.token,"application/json"),400,"INVALID_ARGUMENT");
        error(client.raw("POST",path(w,action),"",client.token,"application/json"),400,"INVALID_ARGUMENT");assertEquals(before,state(w));
    }
    @ParameterizedTest @ValueSource(strings={"notes","close"})
    void maximumUnicodeNoteRoundTrips(String action) throws Exception {
        var w=fresh();accepted(w);var text="🖨".repeat(2000);var r=ok(staffOne().request("POST",path(w,action),Map.of("expectedVersion",1,"note",text)));
        assertEquals(text,r.get("event").get("note").asString());assertEquals(text,mapper.events(w.ticket()).get(2).note());assertEquals(2,mapper.ticket(w.ticket()).version());
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","1.5","9223372036854775808"})
    void invalidIdIs400(String id) throws Exception {error(staffOne().request("POST","/api/tickets/"+id+"/accept",Map.of("expectedVersion",0)),400,"INVALID_ARGUMENT");}
    @ParameterizedTest @ValueSource(strings={"accept","notes","close"})
    void nonexistentTicketIs404(String action) throws Exception {
        error(staffOne().request("POST","/api/tickets/9223372036854775807/"+action,input(action,0)),404,"TICKET_NOT_FOUND");
    }
    @Test void wrongContentTypeRejectedWithoutChange() throws Exception {
        var w=fresh();var before=state(w);var client=staffOne();error(client.raw("POST",path(w,"accept"),"{\"expectedVersion\":0}",client.token,"text/plain"),415,"UNSUPPORTED_MEDIA_TYPE");assertEquals(before,state(w));
    }
    @Test void cannotSkipStatesRepeatOrWriteAfterClosed() throws Exception {
        var w=fresh();var client=staffOne();var pending=state(w);
        for(String action:List.of("notes","close"))error(client.request("POST",path(w,action),input(action,0)),409,"TICKET_CONFLICT");assertEquals(pending,state(w));
        send(client,w,"accept",0);var accepted=state(w);error(client.request("POST",path(w,"accept"),input("accept",1)),409,"TICKET_CONFLICT");assertEquals(accepted,state(w));
        send(client,w,"close",1);var closed=state(w);for(String action:List.of("accept","notes","close"))error(client.request("POST",path(w,action),input(action,2)),409,"TICKET_CONFLICT");assertEquals(closed,state(w));
    }
    @ParameterizedTest @ValueSource(strings={"accept","notes","close"})
    void staleVersionDoesNotOverwriteOrDuplicateEvents(String action) throws Exception {
        var w=fresh();var client=staffOne();if(!action.equals("accept"))accepted(w);
        long actual=mapper.ticket(w.ticket()).version();var before=state(w);
        error(client.request("POST",path(w,action),input(action,actual+1)),409,"TICKET_CONFLICT");assertEquals(before,state(w));
        send(client,w,action,actual);var committed=state(w);error(client.request("POST",path(w,action),input(action,actual)),409,"TICKET_CONFLICT");assertEquals(committed,state(w));
    }
    @ParameterizedTest @ValueSource(strings={"accept","notes","close"})
    void maximumStoredVersionCannotOverflow(String action) throws Exception {
        var w=fresh();if(!action.equals("accept"))accepted(w);jdbc.update("UPDATE tickets SET version=? WHERE id=?",Long.MAX_VALUE,w.ticket());
        var before=state(w);error(staffOne().request("POST",path(w,action),input(action,Long.MAX_VALUE)),409,"TICKET_CONFLICT");assertEquals(before,state(w));
    }
    void lock(java.sql.Connection connection,long ticket) throws Exception {
        try(var s=connection.prepareStatement("SELECT id FROM tickets WHERE id=? FOR UPDATE")){s.setLong(1,ticket);try(var r=s.executeQuery()){assertTrue(r.next());}}
    }
    void race(String firstAction,String secondAction) throws Exception {
        var w=fresh();if(!firstAction.equals("accept"))accepted(w);var before=frozen(w);long version=mapper.ticket(w.ticket()).version();int count=mapper.events(w.ticket()).size();
        var oneClient=staffOne();var twoClient=staffTwo();var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);lock(blocker,w.ticket());
            var one=pool.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return oneClient.request("POST",path(w,firstAction),input(firstAction,version));});
            var two=pool.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return twoClient.request("POST",path(w,secondAction),input(secondAction,version));});
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();assertThrows(TimeoutException.class,()->one.get(200,TimeUnit.MILLISECONDS));assertThrows(TimeoutException.class,()->two.get(200,TimeUnit.MILLISECONDS));
            blocker.commit();var r1=one.get(12,TimeUnit.SECONDS);var r2=two.get(12,TimeUnit.SECONDS);
            assertEquals(List.of(200,409),List.of(r1.statusCode(),r2.statusCode()).stream().sorted().toList());
            boolean firstWon=r1.statusCode()==200;var winner=ok(firstWon?r1:r2);error(firstWon?r2:r1,409,"TICKET_CONFLICT");
            long actor=firstWon?staff:staff2;String action=firstWon?firstAction:secondAction;
            assertEquals(version+1,mapper.ticket(w.ticket()).version());assertEquals(count+1,mapper.events(w.ticket()).size());assertEquals(before,frozen(w));
            var last=mapper.events(w.ticket()).get(count);assertEquals(actor,last.actorId());assertEquals(actor,winner.get("event").get("actorId").asLong());
            if(action.equals("accept"))assertEquals(actor,mapper.ticket(w.ticket()).acceptedBy());
            else assertEquals(staff,mapper.ticket(w.ticket()).acceptedBy());
            if(firstAction.equals("notes") && action.equals("notes"))send(twoClient,w,"close",version+1);
            if(!firstAction.equals("accept")){assertEquals("CLOSED",mapper.ticket(w.ticket()).status());var closed=state(w);error(oneClient.request("POST",path(w,"notes"),input("notes",mapper.ticket(w.ticket()).version())),409,"TICKET_CONFLICT");assertEquals(closed,state(w));}
        } finally {go.countDown();pool.shutdownNow();}
    }
    @Test @Timeout(30) void independentStaffConcurrentAcceptHasOneWinner() throws Exception {race("accept","accept");}
    @Test @Timeout(30) void independentNoteAndCloseCannotWriteAfterClosed() throws Exception {race("notes","close");}
    @Test @Timeout(30) void independentConcurrentCloseHasOneWinner() throws Exception {race("close","close");}
    @ParameterizedTest @ValueSource(strings={"accept","notes","close"})
    void realEventForeignKeyFailureRollsBackThenSameVersionRetries(String action) throws Exception {
        var w=fresh();if(!action.equals("accept"))accepted(w);long version=mapper.ticket(w.ticket()).version();var before=state(w);fault.arm();
        error(staffOne().request("POST",path(w,action),input(action,version)),503,"DATABASE_UNAVAILABLE");
        assertEquals(1452,fault.errorCode);assertEquals("23000",fault.sqlState);assertTrue(fault.constraint.contains("fk_ticket_events_actor"));assertTrue(fault.rolledBack);assertEquals(before,state(w));
        send(staffOne(),w,action,version);assertEquals(version+1,mapper.ticket(w.ticket()).version());
    }
    @Test void originalOperationRestoresCurrentTicketDuringAndAfterHandlingAndExpiry() throws Exception {
        var owner=customerA();var w=work(owner,orderA);var client=staffOne();var frozen=frozen(w);
        for(String action:List.of("accept","notes","close")) {
            long version=mapper.ticket(w.ticket()).version();send(client,w,action,version);
            var get=ok(owner.request("GET","/api/ticket-operations/"+w.operation(),null));
            var retry=ok(owner.request("POST","/api/ticket-operations/"+w.operation()+"/execute",Map.of("confirmationId",w.confirmation(),"draftId",w.draft(),"expectedVersion",0)));
            assertEquals(get,retry);assertEquals("SUCCEEDED",get.get("status").asString());assertEquals(w.ticket(),get.get("ticket").get("id").asLong());
            assertEquals(mapper.ticket(w.ticket()).status(),get.get("ticket").get("status").asString());assertEquals(version+1,get.get("ticket").get("version").asLong());
        }
        assertEquals(frozen,frozen(w));assertEquals(4,mapper.events(w.ticket()).size());
        jdbc.update("UPDATE repair_confirmations SET expires_at='2026-10-27 08:00:00' WHERE id=?",w.confirmation());
        assertEquals("SUCCEEDED",ok(owner.request("GET","/api/ticket-operations/"+w.operation(),null)).get("status").asString());
        ok(owner.request("POST","/api/ticket-operations/"+w.operation()+"/execute",Map.of("confirmationId",w.confirmation(),"draftId",w.draft(),"expectedVersion",0)));
        assertEquals(4,mapper.events(w.ticket()).size());
    }
    @Test void internalServiceAlsoRejectsNonStaff() {
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->handling.handle(new SessionUser.Identity(a,usernameA,"CUSTOMER"),1,0,TicketHandlingService.Action.ACCEPT,null));
    }

    /** 仅测试加载：在真实UPDATE之后将真实事件INSERT的actor改为不存在ID，断言1452与整个事务回滚。 */
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class}))
    static class EventFault implements org.apache.ibatis.plugin.Interceptor {
        volatile boolean armed,rolledBack;volatile int errorCode;volatile String sqlState,constraint;
        void arm(){armed=true;rolledBack=false;errorCode=0;sqlState=constraint=null;}
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var statement=((org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0]).getId();
            if(!armed || !statement.endsWith("TicketMapper.insertEvent"))return invocation.proceed();
            armed=false;
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCompletion(int status){rolledBack=status==STATUS_ROLLED_BACK;}});
            var row=((NewRow<Event>)invocation.getArgs()[1]).getRow();invocation.getArgs()[1]=new NewRow<>(new Event(null,row.ticketId(),Long.MAX_VALUE,row.eventType(),row.note(),row.createdAt()));
            try{return invocation.proceed();}
            catch(Throwable failure){for(Throwable c=failure;c!=null;c=c.getCause())if(c instanceof java.sql.SQLException sql){errorCode=sql.getErrorCode();sqlState=sql.getSQLState();constraint=sql.getMessage();break;}throw failure;}
        }
    }
}

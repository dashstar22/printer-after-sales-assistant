package local.printer.ticket;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
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

/** 独立 MySQL + 真实 HTTP/会话/CSRF/事务；不使用测试外层事务或 Mock。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"AI_ENABLED=false", "AI_API_KEY=", "AI_CHAT_MODEL=", "QDRANT_URL=http://127.0.0.1:9"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TicketOperationIT {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class TimeConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        MutableClock testClock() { return new MutableClock(); }
        @org.springframework.context.annotation.Bean
        OperationFaults operationFaults(DataSource source,org.springframework.transaction.PlatformTransactionManager manager,
                                        org.springframework.beans.factory.ObjectProvider<TicketMapper> mappers) {
            return new OperationFaults(source,manager,mappers);
        }
    }
    static class MutableClock extends java.time.Clock {
        volatile java.time.Instant value = java.time.Instant.parse("2026-10-27T08:00:00.123456Z");
        public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        public java.time.Instant instant() { return value; }
    }
    @Autowired MutableClock clock;
    @Autowired TicketOperationService operations;
    @Autowired OperationFaults faults;
    @Autowired ConfirmationService confirmations;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @BeforeEach void resetClock() { clock.value=java.time.Instant.parse("2026-10-27T08:00:00.123456Z"); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TicketMapper mapper;
    @Autowired DemoSeedService seed;
    @Autowired PasswordEncoder encoder;
    final ObjectMapper json = new ObjectMapper();
    final SeedPasswords passwords = new SeedPasswords(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
    final String usernameA = "operation_a_"+UUID.randomUUID();
    final String usernameB = "operation_b_"+UUID.randomUUID();
    final String usernameStaff = "operation_staff_"+UUID.randomUUID();
    long a, b, orderA, orderB, secondOrderA;

    @BeforeAll void isolatedRealMySql() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("printer_operation_it_"));
        assertEquals("8.4.13", jdbc.queryForObject("SELECT VERSION()", String.class));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("root@"));
        seed.seed(passwords);
        // 每轮追加独立业务测试身份，随机登录密码只在内存；复跑不重置已有账号。
        for (var account : List.of(List.of(usernameA,passwords.customerA(),"CUSTOMER"),
                List.of(usernameB,passwords.customerB(),"CUSTOMER"), List.of(usernameStaff,passwords.staff(),"STAFF"))) {
            jdbc.update("INSERT INTO users(username,password_hash,role) VALUES(?,?,?)",account.get(0),encoder.encode(account.get(1)),account.get(2));
        }
        a = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class,usernameA);
        b = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class,usernameB);
        long model = jdbc.queryForObject("SELECT id FROM device_models WHERE model_code='HL-L2350DW'", Long.class);
        orderA = fixtureOrder(a,model); orderB = fixtureOrder(b,model);
        secondOrderA = fixtureOrder(a,model);
    }

    long fixtureOrder(long customer,long model) {
        var number = "OPERATION-IT-"+UUID.randomUUID();
        jdbc.update("INSERT INTO orders(order_no,customer_id,model_id,serial_no,purchase_date,is_demo) VALUES(?,?,?,?,?,true)",
            number, customer, model, "DEMO-DRAFT-TEST", java.sql.Date.valueOf("2026-10-27"));
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_no=?", Long.class, number);
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
    Map<String,Object> input(long order, Long version) {
        var body = new LinkedHashMap<String,Object>();
        body.put("orderId", order); body.put("symptom", "反复卡纸 🖨"); body.put("extra", "已取纸\n仍报错");
        body.put("contact", "模拟联系人"); body.put("contactMethod", "测试方式待确认");
        if (version != null) body.put("expectedVersion", version);
        return body;
    }
    JsonNode success(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var body = json.readTree(response.body());
        assertEquals(Set.of("id","orderId","symptom","extra","contact","contactMethod","version","order"), body.propertyNames());
        assertEquals(Set.of("id","orderNo","modelCode","brand","serialNo","purchaseDate","orderDemo","modelDemo"), body.get("order").propertyNames());
        assertFalse(response.body().contains("customerId"));
        assertFalse(response.body().contains("password"));
        return body;
    }
    JsonNode error(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        var body = json.readTree(response.body());
        assertEquals(Set.of("code","message","traceId"), body.propertyNames());
        assertEquals(code, body.get("code").asString());
        assertFalse(body.get("traceId").asString().isBlank());
        assertEquals(body.get("traceId").asString(), response.headers().firstValue("X-Trace-Id").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(response.body().contains("模拟联系人"));
        assertFalse(response.body().contains("SELECT"));
        return body;
    }
    long create(Browser browser, long order) throws Exception {
        return success(browser.request("POST", "/api/repair-drafts", input(order, null)), 201).get("id").asLong();
    }
    String path(long id) { return "/api/repair-drafts/"+id; }
    List<Integer> counts() {
        return List.of("repair_drafts","repair_confirmations","tickets","ticket_events").stream()
            .map(table -> jdbc.queryForObject("SELECT COUNT(*) FROM "+table, Integer.class)).toList();
    }
    int max(String field) { return switch(field) { case "contact" -> 100; case "contactMethod" -> 200; default -> 2000; }; }

    String confirmationPath(long id) { return path(id)+"/confirmations"; }
    Map<String,Object> action(long version) { return new LinkedHashMap<>(Map.of("confirmed",true,"expectedVersion",version)); }
    JsonNode confirmation(HttpResponse<String> response,int status) throws Exception {
        assertEquals(status,response.statusCode(),response.body());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        var body=json.readTree(response.body());
        assertEquals(Set.of("confirmationId","operationId","draftId","draftVersion","expiresAt","ticketId"),body.propertyNames());
        assertEquals(4,UUID.fromString(body.get("confirmationId").asString()).version());
        assertEquals(4,UUID.fromString(body.get("operationId").asString()).version());
        assertNotEquals(body.get("confirmationId"),body.get("operationId"));
        assertTrue(body.get("expiresAt").asString().endsWith("Z"));
        return body;
    }
    TicketRows.Confirmation persisted(JsonNode body) { return mapper.confirmation(body.get("confirmationId").asString()); }
    TicketRows.Confirmation validate(long customer,long id,TicketRows.Confirmation row) {
        return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status ->
            confirmations.validatePending(customer,id,row==null?null:row.id(),row==null?null:row.operationId()));
    }
    TicketRows.Confirmation confirm(Browser browser,long id,long version) throws Exception {
        return persisted(confirmation(browser.request("POST",confirmationPath(id),action(version)),201));
    }
    NewRow<Ticket> ticketFixture(long id,String state) {
        var d=mapper.draft(id);
        var ticket=new NewRow<>(new Ticket(null,"CONFIRM-TEST-"+UUID.randomUUID(),id,d.customerId(),d.orderId(),
            d.symptom(),d.extra(),d.contact(),d.contactMethod(),state,0,null));
        mapper.insertTicket(ticket);return ticket;
    }

    String operationPath(Confirmation row) { return "/api/ticket-operations/"+row.operationId(); }
    Map<String,Object> executeBody(Confirmation row) { return new LinkedHashMap<>(Map.of("confirmationId",row.id(),"draftId",row.draftId(),"expectedVersion",row.draftVersion())); }
    HttpResponse<String> execute(Browser browser,Confirmation row) throws Exception {
        return browser.request("POST",operationPath(row)+"/execute",executeBody(row));
    }
    JsonNode operation(HttpResponse<String> response,int status,String state) throws Exception {
        assertEquals(status,response.statusCode(),response.body());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        var body=json.readTree(response.body());
        assertEquals(Set.of("operationId","confirmationId","draftId","draftVersion","status","expiresAt","ticket"),body.propertyNames());
        assertEquals(state,body.get("status").asString());
        assertFalse(response.body().contains("customerId"));assertFalse(response.body().contains("snapshot"));
        assertFalse(response.body().contains("模拟联系人"));assertTrue(body.get("expiresAt").asString().endsWith("Z"));
        if(state.equals("SUCCEEDED")) assertEquals(Set.of("id","ticketNo","status","version"),body.get("ticket").propertyNames());
        else assertTrue(body.get("ticket").isNull());
        return body;
    }
    void consistent(Confirmation row,long ticketId) {
        var d=mapper.draft(row.draftId());var ticket=mapper.ticket(ticketId);
        assertEquals(ticketId,mapper.confirmation(row.id()).ticketId());assertEquals(d.customerId(),ticket.customerId());
        assertEquals(d.orderId(),ticket.orderId());assertEquals(d.id(),ticket.draftId());
        assertEquals(d.symptom(),ticket.snapshotSymptom());assertEquals(d.extra(),ticket.snapshotExtra());
        assertEquals(d.contact(),ticket.snapshotContact());assertEquals(d.contactMethod(),ticket.snapshotContactMethod());
        assertEquals("PENDING",ticket.status());assertEquals(0,ticket.version());assertNull(ticket.acceptedBy());
        assertTrue(ticket.ticketNo().matches("TK-[0-9a-f-]{36}"));
        var events=mapper.events(ticketId);assertEquals(1,events.size());var event=events.get(0);
        assertEquals(d.customerId(),event.actorId());assertEquals("CREATED",event.eventType());
        assertEquals(TicketOperationService.CREATED_NOTE,event.note());
        assertEquals(clock.instant(),event.createdAt().toInstant(java.time.ZoneOffset.UTC));
    }
    Confirmation anotherValidOperation(Confirmation row) {
        var other=new Confirmation(UUID.randomUUID().toString(),row.draftId(),row.customerId(),row.draftVersion(),
            row.payloadHash(),row.expiresAt(),UUID.randomUUID().toString(),null);
        mapper.insertConfirmation(other);return other;
    }
    @Test void bothCustomersCreateAtomicSnapshotEventAndAssociationAndRecover() throws Exception {
        for(boolean asA:List.of(true,false)) {
            var browser=asA?customerA():customerB();long id=create(browser,asA?orderA:orderB);var row=confirm(browser,id,0);
            var before=counts();assertEquals(row,mapper.ownedOperation(row.operationId(),asA?a:b));
            var ready=operation(browser.request("GET",operationPath(row),null),200,"READY");assertEquals(before,counts());
            var result=operation(execute(browser,row),201,"SUCCEEDED");long ticketId=result.get("ticket").get("id").asLong();
            consistent(row,ticketId);assertEquals(before.get(2)+1,counts().get(2));assertEquals(before.get(3)+1,counts().get(3));
            assertEquals(ready.get("expiresAt"),result.get("expiresAt"));
            var after=counts();assertEquals(result,operation(execute(browser,row),200,"SUCCEEDED"));
            assertEquals(result,operation(browser.request("GET",operationPath(row),null),200,"SUCCEEDED"));assertEquals(after,counts());
            error(browser.request("PUT",path(id),input(asA?orderA:orderB,0L)),409,"DRAFT_ALREADY_SUBMITTED");
            var again=persisted(confirmation(browser.request("POST",confirmationPath(id),action(0)),200));
            assertEquals(row.id(),again.id());assertEquals(ticketId,again.ticketId());
        }
    }
    @Test void completedResultSurvivesExpiryWithoutChangingAnything() throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);
        var result=operation(execute(browser,row),201,"SUCCEEDED");var before=counts();var snapshot=mapper.ticket(mapper.confirmation(row.id()).ticketId());
        clock.value=row.expiresAt().toInstant(java.time.ZoneOffset.UTC).plusSeconds(100);
        assertEquals(result,operation(browser.request("GET",operationPath(row),null),200,"SUCCEEDED"));
        assertEquals(result,operation(execute(browser,row),200,"SUCCEEDED"));assertEquals(before,counts());
        assertEquals(snapshot,mapper.ticket(snapshot.id()));
    }
    @ParameterizedTest @ValueSource(strings={"before","boundary","after"})
    void unexecutedExpiryBoundaryIsReadOnlyAndExecutionIsRejectedAtExpiry(String position) throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);var before=counts();
        long offset=position.equals("before")?-1000:position.equals("after")?1000:0;
        clock.value=row.expiresAt().toInstant(java.time.ZoneOffset.UTC).plusNanos(offset);
        operation(browser.request("GET",operationPath(row),null),200,offset<0?"READY":"EXPIRED");assertEquals(before,counts());
        if(offset<0) operation(execute(browser,row),201,"SUCCEEDED");
        else {error(execute(browser,row),409,"CONFIRMATION_EXPIRED");assertEquals(before,counts());assertNull(mapper.confirmation(row.id()).ticketId());}
    }
    @Test void foreignAndMissingOperationsAreUniform404BothDirections() throws Exception {
        var first=customerA();var second=customerB();var ra=confirm(first,create(first,orderA),0);var rb=confirm(second,create(second,orderB),0);
        var before=counts();
        for(boolean asA:List.of(true,false)) {
            var browser=asA?first:second;var foreign=asA?rb:ra;
            for(String method:List.of("GET","POST")) {
                String suffix=method.equals("POST")?"/execute":"";
                var missing=error(browser.request(method,"/api/ticket-operations/"+UUID.randomUUID()+suffix,method.equals("POST")?executeBody(foreign):null),404,"OPERATION_NOT_FOUND");
                var other=error(browser.request(method,operationPath(foreign)+suffix+"?customerId="+(asA?b:a)+"&role=CUSTOMER",method.equals("POST")?executeBody(foreign):null),404,"OPERATION_NOT_FOUND");
                assertEquals(missing.get("message"),other.get("message"));
            }
        }
        assertEquals(before,counts());
    }
    @Test void anonymousStaffCsrfAndHeadAreProtected() throws Exception {
        var owner=customerA();var row=confirm(owner,create(owner,orderA),0);var before=counts();
        var anonymous=new Browser();anonymous.token=anonymous.csrf();var staff=login(usernameStaff,passwords.staff());
        for(boolean asStaff:List.of(false,true)) {
            var browser=asStaff?staff:anonymous;int status=asStaff?403:401;String code=asStaff?"FORBIDDEN":"UNAUTHENTICATED";
            error(browser.request("GET",operationPath(row),null),status,code);error(execute(browser,row),status,code);
            assertEquals(status,browser.raw("HEAD",operationPath(row),null,null,null).statusCode());
        }
        for(String token:Arrays.asList(null,"invalid")) error(owner.raw("POST",operationPath(row)+"/execute",json.writeValueAsString(executeBody(row)),token,"application/json"),403,"CSRF_INVALID");
        assertEquals(before,counts());assertEquals(200,owner.raw("HEAD",operationPath(row),null,null,null).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"customerId","userId","role","payloadHash","expiresAt","ticketNo","symptom"})
    void forgedTrustedFieldsCannotRedefineOperation(String field) throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);var before=counts();
        var body=executeBody(row);body.put(field,"forged");error(browser.request("POST",operationPath(row)+"/execute",body),400,"INVALID_ARGUMENT");
        assertEquals(before,counts());
    }
    @Test void bindingInputTypesMissingFieldsAndMalformedJsonAre400() throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);var before=counts();
        for(String field:List.of("confirmationId","draftId","expectedVersion")) {
            var body=executeBody(row);body.remove(field);error(browser.request("POST",operationPath(row)+"/execute",body),400,"INVALID_ARGUMENT");
            for(Object value:Arrays.asList(null,true,List.of(1),Map.of("value",1),1.5,new java.math.BigInteger("9223372036854775808"))) {
                body=executeBody(row);body.put(field,value);error(browser.request("POST",operationPath(row)+"/execute",body),400,"INVALID_ARGUMENT");
            }
        }
        for(String invalid:List.of("abc","1-1-1-1-1",row.operationId().toUpperCase())) {
            error(browser.request("GET","/api/ticket-operations/"+invalid,null),400,"INVALID_ARGUMENT");
            error(browser.request("POST","/api/ticket-operations/"+invalid+"/execute",executeBody(row)),400,"INVALID_ARGUMENT");
        }
        for(String body:List.of("","{","null","[]","true","42")) error(browser.raw("POST",operationPath(row)+"/execute",body,browser.token,"application/json"),400,"INVALID_ARGUMENT");
        error(browser.raw("POST",operationPath(row)+"/execute","x",browser.token,"text/plain"),415,"UNSUPPORTED_MEDIA_TYPE");
        assertEquals(before,counts());
    }
    @Test void wrongConfirmationDraftOrExpectedVersionCannotReuseOperationBeforeOrAfterSuccess() throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);long other=create(browser,orderA);
        for(boolean succeeded:List.of(false,true)) {
            if(succeeded) operation(execute(browser,row),201,"SUCCEEDED");var before=counts();
            for(String field:List.of("confirmationId","draftId","expectedVersion")) {
                var body=executeBody(row);body.put(field,field.equals("confirmationId")?UUID.randomUUID().toString():field.equals("draftId")?other:1L);
                error(browser.request("POST",operationPath(row)+"/execute",body),409,"CONFIRMATION_CONFLICT");
            }
            assertEquals(before,counts());
        }
    }
    @Test void draftWithoutAnyConfirmationAndForgedIdsCannotExecute() throws Exception {
        var browser=customerA();long id=create(browser,orderA);var before=counts();
        error(browser.request("POST","/api/ticket-operations/"+UUID.randomUUID()+"/execute",Map.of("confirmationId",UUID.randomUUID().toString(),"draftId",id,"expectedVersion",0)),404,"OPERATION_NOT_FOUND");
        assertEquals(before,counts());
    }
    @ParameterizedTest @ValueSource(strings={"order_id","version","symptom","extra","contact","contact_method"})
    void everyChangedBindingIsRejectedByGetAndExecuteWithoutPartialWrites(String field) throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);
        Object value=switch(field){case "order_id"->secondOrderA;case "version"->1L;default->"变化后内容";};
        jdbc.update("UPDATE repair_drafts SET "+field+"=? WHERE id=?",value,row.draftId());var before=counts();
        error(execute(browser,row),409,"CONFIRMATION_CONFLICT");error(browser.request("GET",operationPath(row),null),409,"CONFIRMATION_CONFLICT");
        assertEquals(before,counts());assertNull(mapper.confirmation(row.id()).ticketId());
    }
    @Test void blankStoredContentCannotPassFinalCreationValidation() throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);
        jdbc.update("UPDATE repair_drafts SET extra='   ' WHERE id=?",row.draftId());var before=counts();
        error(execute(browser,row),409,"CONFIRMATION_CONFLICT");assertEquals(before,counts());
    }
    @Test void ordinaryEditInvalidatesOperationUntilNewConfirmation() throws Exception {
        var browser=customerA();long id=create(browser,orderA);var old=confirm(browser,id,0);
        success(browser.request("PUT",path(id),input(orderA,0L)),200);var before=counts();
        error(execute(browser,old),409,"CONFIRMATION_CONFLICT");assertEquals(before,counts());
        var fresh=confirm(browser,id,1);operation(execute(browser,fresh),201,"SUCCEEDED");
    }
    @Test void succeededResultStillChecksSnapshotAndOwnerBinding() throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);operation(execute(browser,row),201,"SUCCEEDED");
        long ticket=mapper.confirmation(row.id()).ticketId();jdbc.update("UPDATE tickets SET snapshot_extra='隔离篡改' WHERE id=?",ticket);
        var before=counts();error(execute(browser,row),409,"CONFIRMATION_CONFLICT");error(browser.request("GET",operationPath(row),null),409,"CONFIRMATION_CONFLICT");assertEquals(before,counts());
    }
    @Test void secondUnexecutedExpiredOperationCannotBorrowExistingTicket() throws Exception {
        var browser=customerA();var first=confirm(browser,create(browser,orderA),0);var second=anotherValidOperation(first);
        operation(execute(browser,first),201,"SUCCEEDED");clock.value=second.expiresAt().toInstant(java.time.ZoneOffset.UTC);
        var before=counts();operation(browser.request("GET",operationPath(second),null),200,"EXPIRED");
        error(execute(browser,second),409,"CONFIRMATION_EXPIRED");assertEquals(before,counts());assertNull(mapper.confirmation(second.id()).ticketId());
    }
    @Test @Timeout(30)
    void sameOperationIndependentHttpTransactionsReturnSingleTicketAndEvent() throws Exception { concurrentExecutions(false); }
    @Test @Timeout(30)
    void differentValidOperationsIndependentHttpTransactionsAssociateSameTicketAndSingleEvent() throws Exception { concurrentExecutions(true); }
    void concurrentExecutions(boolean different) throws Exception {
        var first=customerA();var second=customerA();var oneRow=confirm(first,create(first,orderA),0);
        var twoRow=different?anotherValidOperation(oneRow):oneRow;var before=counts();var pool=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);lock(blocker,oneRow.draftId());
            var one=pool.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return execute(first,oneRow);});
            var two=pool.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return execute(second,twoRow);});
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            assertThrows(TimeoutException.class,()->one.get(200,TimeUnit.MILLISECONDS));assertThrows(TimeoutException.class,()->two.get(200,TimeUnit.MILLISECONDS));
            blocker.commit();var r1=one.get(12,TimeUnit.SECONDS);var r2=two.get(12,TimeUnit.SECONDS);
            assertEquals(List.of(200,201),List.of(r1.statusCode(),r2.statusCode()).stream().sorted().toList());
            var b1=operation(r1,r1.statusCode(),"SUCCEEDED");var b2=operation(r2,r2.statusCode(),"SUCCEEDED");assertEquals(b1.get("ticket"),b2.get("ticket"));
            long ticket=b1.get("ticket").get("id").asLong();consistent(oneRow,ticket);consistent(twoRow,ticket);
            assertEquals(before.get(2)+1,counts().get(2));assertEquals(before.get(3)+1,counts().get(3));
            assertEquals(b1,operation(first.request("GET",operationPath(oneRow),null),200,"SUCCEEDED"));
            assertEquals(b2,operation(second.request("GET",operationPath(twoRow),null),200,"SUCCEEDED"));
        } finally {go.countDown();pool.shutdownNow();}
    }
    void lock(java.sql.Connection blocker,long id) throws Exception {
        try(var q=blocker.prepareStatement("SELECT id FROM repair_drafts WHERE id=? FOR UPDATE")){q.setLong(1,id);try(var r=q.executeQuery()){assertTrue(r.next());}}
    }
    @Test @Timeout(30)
    void executeAndDraftEditRaceHasNoSnapshotVersionMismatch() throws Exception {
        var browser=customerA();var editor=customerA();var row=confirm(browser,create(browser,orderA),0);var original=mapper.draft(row.draftId());
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);lock(blocker,row.draftId());
            var execute=pool.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return execute(browser,row);});
            var edit=pool.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));var body=input(orderA,0L);body.put("symptom","竞争的新内容");return editor.request("PUT",path(row.draftId()),body);});
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();assertThrows(TimeoutException.class,()->execute.get(200,TimeUnit.MILLISECONDS));assertThrows(TimeoutException.class,()->edit.get(200,TimeUnit.MILLISECONDS));blocker.commit();
            var er=execute.get(12,TimeUnit.SECONDS);var ur=edit.get(12,TimeUnit.SECONDS);
            if(er.statusCode()==201){var body=operation(er,201,"SUCCEEDED");error(ur,409,"DRAFT_ALREADY_SUBMITTED");assertEquals(original,mapper.draft(row.draftId()));consistent(row,body.get("ticket").get("id").asLong());}
            else {error(er,409,"CONFIRMATION_CONFLICT");success(ur,200);assertEquals(1,mapper.draft(row.draftId()).version());assertNull(mapper.confirmation(row.id()).ticketId());assertNull(mapper.linkedTicket(row.draftId()));}
        } finally {go.countDown();pool.shutdownNow();}
    }
    @Test @Timeout(20)
    void committedRequestWithUnreadResponseIsRecoveredThroughOriginalOperation() throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);var before=counts();
        byte[] body=json.writeValueAsString(executeBody(row)).getBytes(StandardCharsets.UTF_8);
        var cookies=((CookieManager)browser.client.cookieHandler().orElseThrow()).getCookieStore().getCookies();
        String cookie=cookies.stream().map(c->c.getName()+"="+c.getValue()).collect(java.util.stream.Collectors.joining("; "));
        try(var socket=new java.net.Socket("127.0.0.1",port)) {
            var out=socket.getOutputStream();String header="POST "+operationPath(row)+"/execute HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nCookie: "+cookie+"\r\nX-CSRF-TOKEN: "+browser.token+"\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));out.write(body);out.flush();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(mapper.confirmation(row.id()).ticketId()==null && System.nanoTime()<until) Thread.sleep(25);
            assertNotNull(mapper.confirmation(row.id()).ticketId());
            // 客户端从未读取成功响应，直接关闭连接；随后只使用原operationId恢复。
        }
        var result=operation(browser.request("GET",operationPath(row),null),200,"SUCCEEDED");
        assertEquals(result,operation(execute(browser,row),200,"SUCCEEDED"));assertEquals(before.get(2)+1,counts().get(2));assertEquals(before.get(3)+1,counts().get(3));
    }
    @ParameterizedTest @ValueSource(strings={"EVENT_FK","ASSOCIATION_FK"})
    void realSqlFailureAfterTicketInsertRollsBackAllRowsThenOriginalOperationRetries(String mode) throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);var before=counts();faults.arm(mode,null);
        error(execute(browser,row),503,"DATABASE_UNAVAILABLE");assertEquals(before,counts());assertNull(mapper.confirmation(row.id()).ticketId());assertNull(mapper.linkedTicket(row.draftId()));
        assertEquals(1452,faults.errorCode);assertEquals("23000",faults.sqlState);assertTrue(faults.rolledBack);
        assertTrue(faults.constraint.contains(mode.equals("EVENT_FK")?"fk_ticket_events_actor":"fk_repair_confirmations_ticket"));
        operation(execute(browser,row),201,"SUCCEEDED");consistent(row,mapper.confirmation(row.id()).ticketId());
    }
    @Test void realDuplicateDraftRollbackThenNewTransactionRecoversCommittedWinner() throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);var before=counts();
        faults.arm("DUP_DRAFT_WINNER",null);var attempted=execute(browser,row);
        assertTrue(faults.rolledBack,"rollback callback");assertTrue(faults.winnerCommitted,"winner commit");
        assertNotNull(mapper.linkedTicket(row.draftId()),"winner ticket visible");
        assertEquals(1,mapper.events(mapper.linkedTicket(row.draftId())).size(),"winner event visible");
        operation(attempted,200,"SUCCEEDED");
        assertEquals(1062,faults.errorCode);assertEquals("23000",faults.sqlState);assertTrue(faults.constraint.contains("uk_tickets_draft"));
        assertTrue(faults.rolledBack);assertTrue(faults.winnerCommitted);assertTrue(faults.recoveryCommitted);
        assertNotSame(faults.failedResource,faults.recoveryResource);consistent(row,mapper.confirmation(row.id()).ticketId());
        assertEquals(before.get(2)+1,counts().get(2));assertEquals(before.get(3)+1,counts().get(3));
    }
    @Test void duplicateDraftWithoutCommittedWinnerFailsClosedInNewTransaction() throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);var before=counts();
        faults.arm("DUP_DRAFT_EMPTY",null);error(execute(browser,row),409,"OPERATION_CONFLICT");
        assertEquals(1062,faults.errorCode);assertTrue(faults.constraint.contains("uk_tickets_draft"));assertTrue(faults.rolledBack);assertEquals(before,counts());assertNull(mapper.confirmation(row.id()).ticketId());
        operation(execute(browser,row),201,"SUCCEEDED");
    }
    @Test void ticketNumberUniqueConflictDoesNotMasqueradeAsSuccessfulDraftRecovery() throws Exception {
        var browser=customerA();var row=confirm(browser,create(browser,orderA),0);long other=create(browser,orderA);var before=counts();
        faults.arm("DUP_NO",other);error(execute(browser,row),409,"OPERATION_CONFLICT");assertEquals(1062,faults.errorCode);
        assertTrue(faults.constraint.contains("uk_tickets_no"));assertTrue(faults.rolledBack);assertFalse(faults.winnerCommitted);assertEquals(before,counts());
        operation(execute(browser,row),201,"SUCCEEDED");
    }

    /** Test-only plugin: all failures and winners use real SQL/transactions, no Mock DB or production hook. */
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class}))
    static class OperationFaults implements org.apache.ibatis.plugin.Interceptor {
        final DataSource source;final org.springframework.transaction.PlatformTransactionManager manager;
        final org.springframework.beans.factory.ObjectProvider<TicketMapper> mappers;
        volatile String mode;volatile Long otherDraft;volatile int errorCode;volatile String sqlState,constraint;
        volatile boolean rolledBack,winnerCommitted,recoveryCommitted;volatile Object failedResource,recoveryResource;
        OperationFaults(DataSource source,org.springframework.transaction.PlatformTransactionManager manager,org.springframework.beans.factory.ObjectProvider<TicketMapper> mappers){this.source=source;this.manager=manager;this.mappers=mappers;}
        void arm(String value,Long other){mode=value;otherDraft=other;errorCode=0;sqlState=null;constraint=null;rolledBack=winnerCommitted=recoveryCommitted=false;failedResource=recoveryResource=null;}
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            String statement=((org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0]).getId();String selected=mode;
            if(selected==null) {
                if(rolledBack && winnerCommitted && statement.endsWith(".associateConfirmation")) {
                    recoveryResource=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(source);
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCompletion(int status){recoveryCommitted=status==STATUS_COMMITTED;}});
                }
                return invocation.proceed();
            }
            boolean target=(selected.startsWith("DUP_") && statement.endsWith(".insertTicket")) || (selected.equals("EVENT_FK") && statement.endsWith(".insertEvent")) || (selected.equals("ASSOCIATION_FK") && statement.endsWith(".associateConfirmation"));
            if(!target)return invocation.proceed();mode=null;
            failedResource=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(source);
            Ticket winner=selected.startsWith("DUP_")?((NewRow<Ticket>)invocation.getArgs()[1]).getRow():null;
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                public void afterCompletion(int status){
                    rolledBack=status==STATUS_ROLLED_BACK;
                    if(rolledBack && selected.equals("DUP_DRAFT_WINNER")) {
                        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        tx.executeWithoutResult(s->{var mapper=mappers.getObject();var value=new NewRow<>(winner);mapper.insertTicket(value);
                            mapper.insertEvent(new NewRow<>(new Event(null,value.getId(),winner.customerId(),"CREATED",TicketOperationService.CREATED_NOTE,java.time.LocalDateTime.of(2026,10,27,8,0,0,123456000))));});winnerCommitted=true;
                    }
                }
            });
            try {
                if(selected.equals("EVENT_FK")) {
                    var row=((NewRow<Event>)invocation.getArgs()[1]).getRow();invocation.getArgs()[1]=new NewRow<>(new Event(null,row.ticketId(),Long.MAX_VALUE,row.eventType(),row.note(),row.createdAt()));return invocation.proceed();
                }
                if(selected.equals("ASSOCIATION_FK")) {((Map<String,Object>)invocation.getArgs()[1]).put("ticketId",Long.MAX_VALUE);return invocation.proceed();}
                invocation.proceed();
                if(selected.equals("DUP_NO")) invocation.getArgs()[1]=new NewRow<>(new Ticket(null,winner.ticketNo(),otherDraft,winner.customerId(),winner.orderId(),winner.snapshotSymptom(),winner.snapshotExtra(),winner.snapshotContact(),winner.snapshotContactMethod(),winner.status(),winner.version(),winner.acceptedBy()));
                else invocation.getArgs()[1]=new NewRow<>(new Ticket(null,"TK-"+UUID.randomUUID(),winner.draftId(),winner.customerId(),winner.orderId(),winner.snapshotSymptom(),winner.snapshotExtra(),winner.snapshotContact(),winner.snapshotContactMethod(),winner.status(),winner.version(),winner.acceptedBy()));
                return invocation.proceed(); // 第二次真实INSERT触发1062，第一条随整个事务回滚。
            } catch(Throwable failure) {
                for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof java.sql.SQLException sql){errorCode=sql.getErrorCode();sqlState=sql.getSQLState();constraint=sql.getMessage();break;}
                throw failure;
            }
        }
    }
}

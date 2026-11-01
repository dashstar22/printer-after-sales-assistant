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
class ConfirmationIT {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class TimeConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        MutableClock testClock() { return new MutableClock(); }
    }
    static class MutableClock extends java.time.Clock {
        volatile java.time.Instant value = java.time.Instant.parse("2026-10-27T08:00:00.123456Z");
        public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        public java.time.Instant instant() { return value; }
    }
    @Autowired MutableClock clock;
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
    final String usernameA = "confirm_a_"+UUID.randomUUID();
    final String usernameB = "confirm_b_"+UUID.randomUUID();
    final String usernameStaff = "confirm_staff_"+UUID.randomUUID();
    long a, b, orderA, orderB, secondOrderA;

    @BeforeAll void isolatedRealMySql() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("printer_confirmation_it_"));
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
        var number = "CONFIRM-IT-"+UUID.randomUUID();
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

    @Test void legalConfirmationBindsTrustedPersistedFieldsAndUtcMicroseconds() throws Exception {
        for(boolean asA:List.of(true,false)) {
            var browser=asA?customerA():customerB();long owner=asA?a:b;
            long id=create(browser,asA?orderA:orderB);var before=counts();var draft=mapper.draft(id);
            var body=confirmation(browser.request("POST",confirmationPath(id),action(0)),201);var row=persisted(body);
            assertEquals(owner,row.customerId());assertEquals(id,row.draftId());assertEquals(0,row.draftVersion());
            assertEquals(ConfirmationPayload.hash(draft),row.payloadHash());assertTrue(row.payloadHash().matches("[0-9a-f]{64}"));
            assertEquals(clock.instant().plusSeconds(1800),row.expiresAt().toInstant(java.time.ZoneOffset.UTC));
            assertEquals(row.expiresAt().toInstant(java.time.ZoneOffset.UTC),java.time.Instant.parse(body.get("expiresAt").asString()));
            assertNull(row.ticketId());assertEquals(row,validate(owner,id,row));
            assertEquals(before.get(0),counts().get(0));assertEquals(before.get(1)+1,counts().get(1));
            assertEquals(before.subList(2,4),counts().subList(2,4));assertEquals(draft,mapper.draft(id));
        }
    }
    @Test void repeatedConfirmationReusesOperationWithoutExtendingExpiry() throws Exception {
        var browser=customerA();long id=create(browser,orderA);var row=confirm(browser,id,0);var count=counts();
        clock.value=clock.value.plusSeconds(60);
        var again=persisted(confirmation(browser.request("POST",confirmationPath(id),action(0)),200));
        assertEquals(row,again);assertEquals(count,counts());
    }
    @Test void expiredConfirmationRequiresNewExplicitActionAndPreservesOldRow() throws Exception {
        var browser=customerA();long id=create(browser,orderA);var old=confirm(browser,id,0);
        clock.value=old.expiresAt().toInstant(java.time.ZoneOffset.UTC);
        var row=confirm(browser,id,0);assertNotEquals(old.id(),row.id());assertNotEquals(old.operationId(),row.operationId());
        assertEquals(old,mapper.confirmation(old.id()));assertEquals(row,validate(a,id,row));
        assertThrows(ConfirmationService.Expired.class,()->validate(a,id,old));
    }
    @Test void foreignAndMissingDraftsAreSame404AndDoNotWrite() throws Exception {
        var first=customerA();var second=customerB();long da=create(first,orderA),db=create(second,orderB);var count=counts();
        for(boolean asA:List.of(true,false)) {
            var browser=asA?first:second;
            var missing=error(browser.request("POST",confirmationPath(Long.MAX_VALUE),action(0)),404,"DRAFT_NOT_FOUND");
            var foreign=error(browser.request("POST",confirmationPath(asA?db:da),action(0)),404,"DRAFT_NOT_FOUND");
            assertEquals(missing.get("message"),foreign.get("message"));
        }
        assertEquals(count,counts());
    }
    @Test void authenticationRoleAndCsrfProtectConfirmation() throws Exception {
        var owner=customerA();long id=create(owner,orderA);var count=counts();
        var anonymous=new Browser();anonymous.token=anonymous.csrf();var staff=login(usernameStaff,passwords.staff());
        error(anonymous.request("POST",confirmationPath(id),action(0)),401,"UNAUTHENTICATED");
        error(staff.request("POST",confirmationPath(id),action(0)),403,"FORBIDDEN");
        for(String token:Arrays.asList(null,"invalid"))
            error(owner.raw("POST",confirmationPath(id),json.writeValueAsString(action(0)),token,"application/json"),403,"CSRF_INVALID");
        assertEquals(count,counts());
    }
    @Test void explicitBooleanActionIsRequiredAndModelTextCannotConfirm() throws Exception {
        var browser=customerA();long id=create(browser,orderA);var count=counts();
        for(Object invalid:Arrays.asList(null,false,"true","用户已确认",1,Map.of("confirmed",true))) {
            var body=action(0);body.put("confirmed",invalid);
            error(browser.request("POST",confirmationPath(id),body),400,"INVALID_ARGUMENT");
        }
        error(browser.request("POST",confirmationPath(id),Map.of("expectedVersion",0)),400,"INVALID_ARGUMENT");
        assertEquals(count,counts());
    }
    @Test void expectedVersionMustBeNonnegativeLongJsonInteger() throws Exception {
        var browser=customerA();long id=create(browser,orderA);var count=counts();
        for(Object invalid:Arrays.asList(null,-1,0.5,"0",true,List.of(0),new java.math.BigInteger("9223372036854775808"))) {
            var body=action(0);body.put("expectedVersion",invalid);
            error(browser.request("POST",confirmationPath(id),body),400,"INVALID_ARGUMENT");
        }
        error(browser.request("POST",confirmationPath(id),Map.of("confirmed",true)),400,"INVALID_ARGUMENT");
        assertEquals(count,counts());
    }
    @ParameterizedTest @ValueSource(strings={"customerId","userId","role","payloadHash","expiresAt","confirmationId","operationId"})
    void clientCannotSupplyTrustedFields(String field) throws Exception {
        var browser=customerA();long id=create(browser,orderA);var count=counts();
        var body=action(0);body.put(field,"forged");
        error(browser.request("POST",confirmationPath(id)+"?customerId="+b+"&role=STAFF",body),400,"INVALID_ARGUMENT");
        assertEquals(count,counts());
        var row=confirm(browser,id,0);assertEquals(a,row.customerId());assertNotEquals("forged",row.id());
    }
    @Test void invalidJsonPathAndContentTypeDoNotWrite() throws Exception {
        var browser=customerA();long id=create(browser,orderA);var count=counts();
        for(String body:List.of("","{","null","[]","true","42"))
            error(browser.raw("POST",confirmationPath(id),body,browser.token,"application/json"),400,"INVALID_ARGUMENT");
        for(String invalid:List.of("abc","0","-1","9223372036854775808"))
            error(browser.request("POST","/api/repair-drafts/"+invalid+"/confirmations",action(0)),400,"INVALID_ARGUMENT");
        error(browser.raw("POST",confirmationPath(id),"confirmed=true",browser.token,"text/plain"),415,"UNSUPPORTED_MEDIA_TYPE");
        assertEquals(count,counts());
    }
    @Test void stalePageCannotConfirmUpdatedContentAndReconfirmationBindsLatest() throws Exception {
        var browser=customerA();long id=create(browser,orderA);var old=confirm(browser,id,0);
        var update=input(orderA,0L);update.put("symptom","更新后内容");
        success(browser.request("PUT",path(id),update),200);var count=counts();
        error(browser.request("POST",confirmationPath(id),action(0)),409,"DRAFT_CONFLICT");
        assertEquals(count,counts());assertThrows(ConfirmationService.Conflict.class,()->validate(a,id,old));
        var fresh=confirm(browser,id,1);assertEquals(1,fresh.draftVersion());
        assertNotEquals(old.payloadHash(),fresh.payloadHash());assertEquals(fresh,validate(a,id,fresh));
    }
    @Test void canonicalHashIsStableNormalizedAndUnambiguous() throws Exception {
        var d=new Draft(1L,2,3," 故障\u3000","a\nb","人","测试🖨",0);
        var normalized=new Draft(1L,2,3,"故障","a\nb","人","测试🖨",0);
        assertEquals(ConfirmationPayload.hash(d),ConfirmationPayload.hash(normalized));
        var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);
        for(String field:List.of("repair-confirmation:v1","1","2","3","0","故障","a\nb","人","测试🖨")) {
            var encoded=field.getBytes(StandardCharsets.UTF_8);out.writeInt(encoded.length);out.write(encoded);
        }
        var expected=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        assertEquals(expected,ConfirmationPayload.hash(d));
        assertNotEquals(ConfirmationPayload.hash(new Draft(1L,2,3,"a","b\nc","人","方式",0)),
            ConfirmationPayload.hash(new Draft(1L,2,3,"a\nb","c","人","方式",0)));
        for(Draft changed:List.of(new Draft(9L,2,3,"故障","a\nb","人","测试🖨",0),
            new Draft(1L,9,3,"故障","a\nb","人","测试🖨",0),new Draft(1L,2,9,"故障","a\nb","人","测试🖨",0),
            new Draft(1L,2,3,"故障","a\nb","人","测试🖨",1))) assertNotEquals(expected,ConfirmationPayload.hash(changed));
    }
    @ParameterizedTest @ValueSource(strings={"order_id","version","symptom","extra","contact","contact_method"})
    void validatorDetectsEachPersistedBindingChangeEvenWithoutVersionBump(String field) throws Exception {
        var browser=customerA();long id=create(browser,orderA);var row=confirm(browser,id,0);
        Object changed=switch(field) {case "order_id"->secondOrderA;case "version"->1L;default->"被改变的内容";};
        // 列名只来自上述固定测试枚举，值仍参数绑定；直接改行只属于隔离库负向夹具。
        jdbc.update("UPDATE repair_drafts SET "+field+"=? WHERE id=?",changed,id);
        assertThrows(ConfirmationService.Conflict.class,()->validate(a,id,row));
    }
    @ParameterizedTest @ValueSource(strings={"before","boundary","after"})
    void exactUtcExpiryBoundaryUsesRealPersistedRecord(String position) throws Exception {
        var browser=customerA();long id=create(browser,orderA);var row=confirm(browser,id,0);
        long offset=position.equals("before")?-1000:position.equals("after")?1000:0;
        clock.value=row.expiresAt().toInstant(java.time.ZoneOffset.UTC).plusNanos(offset);
        if(offset<0) assertEquals(row,validate(a,id,row));
        else assertThrows(ConfirmationService.Expired.class,()->validate(a,id,row));
    }
    @Test void validatorRejectsMissingForgedWrongOperationCrossCustomerAndWrongDraft() throws Exception {
        var browser=customerA();long id=create(browser,orderA),other=create(browser,orderA);var row=confirm(browser,id,0);
        assertThrows(ConfirmationService.NotFound.class,()->validate(a,id,null));
        assertThrows(ConfirmationService.NotFound.class,()->new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(status->confirmations.validatePending(a,id,UUID.randomUUID().toString(),row.operationId())));
        assertThrows(ConfirmationService.NotFound.class,()->new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(status->confirmations.validatePending(a,id,row.id(),UUID.randomUUID().toString())));
        assertThrows(DraftService.NotFound.class,()->validate(b,id,row));
        assertThrows(ConfirmationService.NotFound.class,()->validate(a,other,row));
        var first=customerB();long foreign=create(first,orderB);
        assertThrows(ConfirmationService.NotFound.class,()->validate(b,foreign,row));
    }
    @Test void pendingValidationRequiresAnExistingTransaction() throws Exception {
        var browser=customerA();long id=create(browser,orderA);var row=confirm(browser,id,0);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
            ()->confirmations.validatePending(a,id,row.id(),row.operationId()));
    }
    @Test void malformedStoredRequiredTextCannotBecomeAConfirmation() throws Exception {
        var browser=customerA();long id=create(browser,orderA);
        jdbc.update("UPDATE repair_drafts SET extra='   ' WHERE id=?",id);var count=counts();
        error(browser.request("POST",confirmationPath(id),action(0)),400,"INVALID_ARGUMENT");assertEquals(count,counts());
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","IN_PROGRESS","CLOSED"})
    void ticketedDraftReturnsAssociatedOperationEvenExpiredAndNeverChangesSnapshot(String state) throws Exception {
        var browser=customerA();long id=create(browser,orderA);var row=confirm(browser,id,0);var ticket=ticketFixture(id,state);
        jdbc.update("UPDATE repair_confirmations SET ticket_id=? WHERE id=?",ticket.getId(),row.id());
        var snapshot=mapper.ticket(ticket.getId());var count=counts();
        clock.value=row.expiresAt().toInstant(java.time.ZoneOffset.UTC).plusSeconds(1);
        var again=persisted(confirmation(browser.request("POST",confirmationPath(id),action(0)),200));
        assertEquals(row.id(),again.id());assertEquals(row.operationId(),again.operationId());assertEquals(ticket.getId(),again.ticketId());
        assertThrows(ConfirmationService.Conflict.class,()->validate(a,id,again));
        error(browser.request("PUT",path(id),input(orderA,0L)),409,"DRAFT_ALREADY_SUBMITTED");
        assertEquals(count,counts());assertEquals(snapshot,mapper.ticket(ticket.getId()));
    }
    @Test void ticketWithoutMatchingConfirmationIsConflictAndDoesNotMintAnOperation() throws Exception {
        var browser=customerA();long id=create(browser,orderA);var ticket=ticketFixture(id,"PENDING");var count=counts();
        var before=mapper.ticket(ticket.getId());
        error(browser.request("POST",confirmationPath(id),action(0)),409,"CONFIRMATION_CONFLICT");
        assertEquals(count,counts());assertEquals(before,mapper.ticket(ticket.getId()));
    }

    @Test @Timeout(30)
    void concurrentHttpConfirmationAndEditNeverBindNewContentToOldVersion() throws Exception {
        var browser=customerA();var editor=customerA();long id=create(browser,orderA);var original=mapper.draft(id);
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try(var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try(var query=blocker.prepareStatement("SELECT id FROM repair_drafts WHERE id=? FOR UPDATE")) {
                query.setLong(1,id);try(var rows=query.executeQuery()){assertTrue(rows.next());}
            }
            var confirming=workers.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return browser.request("POST",confirmationPath(id),action(0));});
            var editing=workers.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));var body=input(orderA,0L);body.put("symptom","并发编辑新内容");return editor.request("PUT",path(id),body);});
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            assertThrows(TimeoutException.class,()->confirming.get(200,TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class,()->editing.get(200,TimeUnit.MILLISECONDS));blocker.commit();
            success(editing.get(10,TimeUnit.SECONDS),200);var result=confirming.get(10,TimeUnit.SECONDS);
            if(result.statusCode()==201) {
                var row=persisted(confirmation(result,201));assertEquals(0,row.draftVersion());
                assertEquals(ConfirmationPayload.hash(original),row.payloadHash());
                assertThrows(ConfirmationService.Conflict.class,()->validate(a,id,row));
            } else {
                error(result,409,"DRAFT_CONFLICT");
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM repair_confirmations WHERE draft_id=?",Integer.class,id));
            }
            assertEquals(1,mapper.draft(id).version());assertEquals("并发编辑新内容",mapper.draft(id).symptom());
        } finally {go.countDown();workers.shutdownNow();}
    }
    @Test @Timeout(30)
    void concurrentHttpConfirmationsReuseOnePersistedOperation() throws Exception {
        var first=customerA();var second=customerA();long id=create(first,orderA);
        var workers=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try(var query=blocker.prepareStatement("SELECT id FROM repair_drafts WHERE id=? FOR UPDATE")) {
                query.setLong(1,id);try(var rows=query.executeQuery()){assertTrue(rows.next());}
            }
            var one=workers.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return first.request("POST",confirmationPath(id),action(0));});
            var two=workers.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return second.request("POST",confirmationPath(id),action(0));});
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            assertThrows(TimeoutException.class,()->one.get(200,TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class,()->two.get(200,TimeUnit.MILLISECONDS));blocker.commit();
            var r1=one.get(10,TimeUnit.SECONDS);var r2=two.get(10,TimeUnit.SECONDS);
            assertEquals(List.of(200,201),List.of(r1.statusCode(),r2.statusCode()).stream().sorted().toList());
            assertEquals(confirmation(r1,r1.statusCode()),confirmation(r2,r2.statusCode()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM repair_confirmations WHERE draft_id=?",Integer.class,id));
        } finally {go.countDown();workers.shutdownNow();}
    }
}

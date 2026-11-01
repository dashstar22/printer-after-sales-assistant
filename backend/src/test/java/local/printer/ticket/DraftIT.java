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
class DraftIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TicketMapper mapper;
    @Autowired DemoSeedService seed;
    @Autowired PasswordEncoder encoder;
    final ObjectMapper json = new ObjectMapper();
    final SeedPasswords passwords = new SeedPasswords(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
    final String usernameA = "draft_a_"+UUID.randomUUID();
    final String usernameB = "draft_b_"+UUID.randomUUID();
    final String usernameStaff = "draft_staff_"+UUID.randomUUID();
    long a, b, orderA, orderB, secondOrderA;

    @BeforeAll void isolatedRealMySql() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("printer_draft_it_"));
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
        var number = "DRAFT-IT-"+UUID.randomUUID();
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

    @Test void customersCreateAndReadNormalizedFieldsAndOrderContext() throws Exception {
        for (boolean asA : List.of(true,false)) {
            var browser = asA ? customerA() : customerB();
            long order = asA ? orderA : orderB;
            var body = input(order, null);
            body.put("symptom", "\u00a0\u3000 反复卡纸 🖨 \t\n");
            body.put("extra", "  已取纸\n仍报错\u3000");
            body.put("contact", "  模拟联系人  ");
            var created = success(browser.request("POST", "/api/repair-drafts", body), 201);
            long id = created.get("id").asLong();
            assertEquals(created, success(browser.request("GET", path(id), null), 200));
            assertEquals(0, created.get("version").asLong());
            assertEquals(order, created.get("orderId").asLong());
            assertEquals("反复卡纸 🖨", created.get("symptom").asString());
            assertEquals("已取纸\n仍报错", created.get("extra").asString());
            assertEquals("模拟联系人", created.get("contact").asString());
            assertEquals(asA ? a : b, mapper.draft(id).customerId());
            assertEquals(order, created.get("order").get("id").asLong());
            assertEquals("HL-L2350DW", created.get("order").get("modelCode").asString());
            assertEquals("Brother", created.get("order").get("brand").asString());
            assertTrue(created.get("order").get("orderDemo").asBoolean());
        }
    }

    @ParameterizedTest @ValueSource(strings={"symptom","extra","contact","contactMethod"})
    void exactLengthIncludingSupplementaryCharactersIsAccepted(String field) throws Exception {
        var browser = customerA();
        var body = input(orderA, null);
        body.put(field, "🖨".repeat(max(field)));
        var created = success(browser.request("POST", "/api/repair-drafts", body), 201);
        long id = created.get("id").asLong();
        assertEquals(body.get(field), created.get(field).asString());
        body.put("expectedVersion", 0);
        var updated = success(browser.request("PUT", path(id), body), 200);
        assertEquals(1, updated.get("version").asLong());
        assertEquals(body.get(field), success(browser.request("GET", path(id), null), 200).get(field).asString());
    }

    @ParameterizedTest @ValueSource(strings={"symptom","extra","contact","contactMethod"})
    void eachRequiredTextRejectsMissingNullBlankTooLongAndWrongTypeWithoutWrites(String field) throws Exception {
        var browser = customerA();
        long id = create(browser, orderA);
        var before = mapper.draft(id);
        var beforeCounts = counts();
        var invalid = Arrays.asList(null, "", " \t\r\n", "\u00a0\u3000\u202f", "汉".repeat(max(field)+1), 123, true, List.of("text"), Map.of("value","text"));
        for (int i = -1; i < invalid.size(); i++) {
            var body = input(orderA, 0L);
            if (i == -1) body.remove(field); else body.put(field, invalid.get(i));
            error(browser.request("POST", "/api/repair-drafts", body), 400, "INVALID_ARGUMENT");
            error(browser.request("PUT", path(id), body), 400, "INVALID_ARGUMENT");
            assertEquals(before, mapper.draft(id));
            assertEquals(beforeCounts, counts());
        }
    }

    @Test void invalidOrderIdTypesAndRangesDoNotWrite() throws Exception {
        var browser = customerA();
        long id = create(browser, orderA);
        var before = mapper.draft(id);
        var count = counts();
        var invalid = Arrays.asList(null, 0, -1, 1.5, "1", true, List.of(1), Map.of("id",1), new java.math.BigInteger("9223372036854775808"));
        for (int i=-1; i<invalid.size(); i++) {
            var body = input(orderA, 0L);
            if (i==-1) body.remove("orderId"); else body.put("orderId", invalid.get(i));
            error(browser.request("POST", "/api/repair-drafts", body),400,"INVALID_ARGUMENT");
            error(browser.request("PUT", path(id), body),400,"INVALID_ARGUMENT");
            assertEquals(before, mapper.draft(id)); assertEquals(count, counts());
        }
    }

    @Test void updateRequiresNonnegativeIntegralExpectedVersion() throws Exception {
        var browser = customerA();
        long id = create(browser, orderA);
        var before = mapper.draft(id);
        for (Object invalid : Arrays.asList(null, -1, 0.5, "0", true, List.of(0), new java.math.BigInteger("9223372036854775808"))) {
            var body = input(orderA, null);
            body.put("expectedVersion", invalid);
            error(browser.request("PUT", path(id), body),400,"INVALID_ARGUMENT");
            assertEquals(before,mapper.draft(id));
        }
        error(browser.request("PUT",path(id),input(orderA,null)),400,"INVALID_ARGUMENT");
        assertEquals(before,mapper.draft(id));
    }

    @ParameterizedTest @ValueSource(strings={"abc","0","-1","1.5","9223372036854775808"})
    void invalidPathIdsAre400(String id) throws Exception {
        var browser=customerA();
        var before=counts();
        error(browser.request("GET","/api/repair-drafts/"+id,null),400,"INVALID_ARGUMENT");
        error(browser.request("PUT","/api/repair-drafts/"+id,input(orderA,0L)),400,"INVALID_ARGUMENT");
        assertEquals(before,counts());
    }

    @Test void malformedMissingOrNonObjectJsonIsUniform400() throws Exception {
        var browser=customerA();
        long id=create(browser,orderA);
        var before=mapper.draft(id);
        var count=counts();
        for (String body : List.of("", "{", "null", "[]", "42", "true", "\"text\"")) {
            error(browser.raw("POST","/api/repair-drafts",body,browser.token,"application/json"),400,"INVALID_ARGUMENT");
            error(browser.raw("PUT",path(id),body,browser.token,"application/json"),400,"INVALID_ARGUMENT");
        }
        assertEquals(before,mapper.draft(id)); assertEquals(count,counts());
    }

    @Test void wrongContentTypeIs415AndDoesNotWrite() throws Exception {
        var browser=customerA();
        long id=create(browser,orderA);
        var before=mapper.draft(id);
        var count=counts();
        error(browser.raw("POST","/api/repair-drafts","symptom=test",browser.token,"application/x-www-form-urlencoded"),415,"UNSUPPORTED_MEDIA_TYPE");
        error(browser.raw("PUT",path(id),"test",browser.token,"text/plain"),415,"UNSUPPORTED_MEDIA_TYPE");
        assertEquals(before,mapper.draft(id)); assertEquals(count,counts());
    }

    @Test void foreignAndMissingDraftsHaveSame404BothDirections() throws Exception {
        var first=customerA(); var second=customerB();
        long firstId=create(first,orderA), secondId=create(second,orderB);
        var before=List.of(mapper.draft(firstId),mapper.draft(secondId));
        for (boolean asA : List.of(true,false)) {
            var browser=asA?first:second;
            long foreign=asA?secondId:firstId, ownOrder=asA?orderA:orderB;
            for (String method : List.of("GET","PUT")) {
                var body=method.equals("PUT")?input(ownOrder,0L):null;
                var missing=error(browser.request(method,path(Long.MAX_VALUE),body),404,"DRAFT_NOT_FOUND");
                var other=error(browser.request(method,path(foreign),body),404,"DRAFT_NOT_FOUND");
                assertEquals(missing.get("message"),other.get("message"));
            }
        }
        assertEquals(before,List.of(mapper.draft(firstId),mapper.draft(secondId)));
    }

    @Test void creationAndUpdateRejectForeignOrMissingOrdersBothDirections() throws Exception {
        for (boolean asA : List.of(true,false)) {
            var browser=asA?customerA():customerB();
            long own=asA?orderA:orderB, foreign=asA?orderB:orderA;
            long id=create(browser,own);
            var before=mapper.draft(id); var count=counts();
            for (long target : List.of(foreign,Long.MAX_VALUE)) {
                error(browser.request("POST","/api/repair-drafts",input(target,null)),404,"ORDER_NOT_FOUND");
                error(browser.request("PUT",path(id),input(target,0L)),404,"ORDER_NOT_FOUND");
                assertEquals(before,mapper.draft(id)); assertEquals(count,counts());
            }
        }
    }

    @Test void forgedIdentityRoleAndVersionNeverChangeTrustedOwnership() throws Exception {
        var browser=customerA();
        var body=input(orderA,null);
        body.put("customerId",b); body.put("userId",b); body.put("role","STAFF"); body.put("version",999);
        var created=success(browser.request("POST","/api/repair-drafts?customerId="+b+"&role=STAFF",body),201);
        long id=created.get("id").asLong();
        assertEquals(a,mapper.draft(id).customerId()); assertEquals(0,created.get("version").asLong());
        body.put("expectedVersion",0);
        assertEquals(1,success(browser.request("PUT",path(id)+"?userId="+b,body),200).get("version").asLong());
        assertEquals(a,mapper.draft(id).customerId());
        error(customerB().request("GET",path(id)+"?userId="+a+"&role=STAFF",null),404,"DRAFT_NOT_FOUND");
        error(browser.request("POST","/api/repair-drafts",input(orderB,null)),404,"ORDER_NOT_FOUND");
    }

    @Test void anonymousAndStaffCannotUseDraftEndpointsIncludingHead() throws Exception {
        var owner=customerA(); long id=create(owner,orderA);
        var before=mapper.draft(id); var count=counts();
        var anonymous=new Browser(); anonymous.token=anonymous.csrf();
        var staff=login(usernameStaff,passwords.staff());
        for (boolean asStaff:List.of(false,true)) {
            var browser=asStaff?staff:anonymous; int status=asStaff?403:401;
            String code=asStaff?"FORBIDDEN":"UNAUTHENTICATED";
            error(browser.request("GET",path(id),null),status,code);
            error(browser.request("POST","/api/repair-drafts",input(orderA,null)),status,code);
            error(browser.request("PUT",path(id),input(orderA,0L)),status,code);
            assertEquals(status,browser.raw("HEAD",path(id),null,null,null).statusCode());
        }
        assertEquals(200,owner.raw("HEAD",path(id),null,null,null).statusCode());
        assertEquals(404,customerB().raw("HEAD",path(id),null,null,null).statusCode());
        assertEquals(before,mapper.draft(id)); assertEquals(count,counts());
    }

    @Test void writesRequireFreshCsrfAndLogoutRevokesAccess() throws Exception {
        var browser=customerA(); long id=create(browser,orderA);
        var before=mapper.draft(id); var count=counts();
        for (String token:Arrays.asList(null,"invalid")) {
            error(browser.raw("POST","/api/repair-drafts",json.writeValueAsString(input(orderA,null)),token,"application/json"),403,"CSRF_INVALID");
            error(browser.raw("PUT",path(id),json.writeValueAsString(input(orderA,0L)),token,"application/json"),403,"CSRF_INVALID");
        }
        var anonymous=new Browser();
        error(anonymous.request("POST","/api/repair-drafts",input(orderA,null)),403,"CSRF_INVALID");
        assertEquals(200,browser.raw("POST","/api/auth/logout","",browser.token,"application/x-www-form-urlencoded").statusCode());
        error(browser.request("GET",path(id),null),401,"UNAUTHENTICATED");
        error(browser.request("PUT",path(id),input(orderA,0L)),403,"CSRF_INVALID");
        assertEquals(before,mapper.draft(id)); assertEquals(count,counts());
    }

    @Test void updateIncrementsVersionAndStaleRequestCannotOverwrite() throws Exception {
        var browser=customerA(); long id=create(browser,orderA);
        var body=input(orderA,0L); body.put("symptom","新的故障");
        var updated=success(browser.request("PUT",path(id),body),200);
        assertEquals(id,updated.get("id").asLong()); assertEquals(1,updated.get("version").asLong());
        var before=mapper.draft(id);
        body.put("symptom","旧版本不应覆盖");
        error(browser.request("PUT",path(id),body),409,"DRAFT_CONFLICT");
        assertEquals(before,mapper.draft(id));
        body.put("symptom","新的故障");body.put("expectedVersion",1);
        assertEquals(2,success(browser.request("PUT",path(id),body),200).get("version").asLong());
    }

    @Test void orderCanChangeOnlyToAnotherOwnedOrder() throws Exception {
        var browser=customerA(); long id=create(browser,orderA);
        var changed=success(browser.request("PUT",path(id),input(secondOrderA,0L)),200);
        assertEquals(secondOrderA,changed.get("orderId").asLong());
        assertEquals(secondOrderA,changed.get("order").get("id").asLong());
        assertEquals(1,changed.get("version").asLong());
        var before=mapper.draft(id);
        error(browser.request("PUT",path(id),input(orderB,1L)),404,"ORDER_NOT_FOUND");
        assertEquals(before,mapper.draft(id));
    }

    @Test void maximumVersionDoesNotOverflow() throws Exception {
        var browser=customerA(); long id=create(browser,orderA);
        jdbc.update("UPDATE repair_drafts SET version=? WHERE id=?",Long.MAX_VALUE,id);
        var before=mapper.draft(id);
        error(browser.request("PUT",path(id),input(orderA,Long.MAX_VALUE)),409,"DRAFT_CONFLICT");
        assertEquals(before,mapper.draft(id));
    }

    @ParameterizedTest @ValueSource(strings={"PENDING","IN_PROGRESS","CLOSED"})
    void anyLinkedTicketPreventsEditingAndKeepsSnapshot(String status) throws Exception {
        var browser=customerA(); long id=create(browser,orderA);
        var draft=mapper.draft(id);
        var ticket=new NewRow<>(new Ticket(null,"DRAFT-TEST-"+UUID.randomUUID(),id,a,orderA,draft.symptom(),draft.extra(),draft.contact(),draft.contactMethod(),status,0,null));
        mapper.insertTicket(ticket); // 隔离库夹具，不是生产建单入口。
        var snapshot=mapper.ticket(ticket.getId()); var count=counts();
        var body=input(secondOrderA,0L);body.put("symptom","不得改变");
        error(browser.request("PUT",path(id),body),409,"DRAFT_ALREADY_SUBMITTED");
        assertEquals(draft,mapper.draft(id)); assertEquals(snapshot,mapper.ticket(ticket.getId()));
        assertEquals(count,counts());
        assertEquals(draft.symptom(),success(browser.request("GET",path(id),null),200).get("symptom").asString());
    }

    @Test void mapperOwnershipAndVersionConditionsRejectUntrustedUpdates() throws Exception {
        var browser=customerA(); long id=create(browser,orderA);
        assertNull(mapper.ownedDraft(id,b));
        var before=mapper.draft(id);
        assertEquals(0,mapper.updateOwnedDraft(id,new Draft(null,b,orderB,"x","x","x","x",0)));
        assertEquals(0,mapper.updateOwnedDraft(id,new Draft(null,a,orderA,"x","x","x","x",1)));
        assertEquals(before,mapper.draft(id));
    }

    @Test @Timeout(25)
    void twoIndependentHttpTransactionsOnSameVersionHaveExactlyOneWinner() throws Exception {
        var first=customerA(); var second=customerA();
        long id=create(first,orderA);
        var count=counts();
        var ready=new CountDownLatch(2); var go=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        try (var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var query=blocker.prepareStatement("SELECT id FROM repair_drafts WHERE id=? FOR UPDATE")) {
                query.setLong(1,id);try(var rows=query.executeQuery()) { assertTrue(rows.next()); }
            }
            var one=workers.submit(() -> { ready.countDown(); assertTrue(go.await(5,TimeUnit.SECONDS));var body=input(orderA,0L);body.put("symptom","并发内容甲");return first.request("PUT",path(id),body); });
            var two=workers.submit(() -> { ready.countDown(); assertTrue(go.await(5,TimeUnit.SECONDS));var body=input(orderA,0L);body.put("symptom","并发内容乙");return second.request("PUT",path(id),body); });
            assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown();
            // 独立数据库连接持锁时，两条实际HTTP写请求都不能完成。
            assertThrows(TimeoutException.class,()->one.get(200,TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class,()->two.get(200,TimeUnit.MILLISECONDS));
            blocker.commit();
            var responses=List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
            assertEquals(List.of(200,409),responses.stream().map(HttpResponse::statusCode).sorted().toList());
            for(var response:responses) {
                if(response.statusCode()==409) error(response,409,"DRAFT_CONFLICT");
                else {
                    var winner=success(response,200);
                    assertEquals(1,winner.get("version").asLong());
                    assertEquals(winner.get("symptom").asString(),mapper.draft(id).symptom());
                    assertEquals(winner,success(first.request("GET",path(id),null),200));
                }
            }
            assertEquals(1,mapper.draft(id).version()); assertEquals(count,counts());
        } finally { go.countDown();workers.shutdownNow(); }
    }
}

package local.printer.order;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import local.printer.seed.DemoSeedService;
import local.printer.seed.SeedPasswords;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独立库 + 真实 Tomcat/过滤链/会话/SQL；无测试方法事务或 Mock。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"AI_ENABLED=false", "AI_API_KEY=", "AI_CHAT_MODEL=", "QDRANT_URL=http://127.0.0.1:9"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    @Autowired PasswordEncoder encoder;
    final ObjectMapper json = new ObjectMapper();
    final SeedPasswords passwords = new SeedPasswords(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
    final String emptyPassword = UUID.randomUUID().toString();
    long a, b, orderA, orderB;

    @BeforeAll void fixture() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("printer_order_it_"));
        assertEquals("8.4.13", jdbc.queryForObject("SELECT VERSION()", String.class));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("root@"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        seed.seed(passwords);
        a = jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_a'", Long.class);
        b = jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_b'", Long.class);
        orderA = jdbc.queryForObject("SELECT id FROM orders WHERE customer_id=?", Long.class, a);
        orderB = jdbc.queryForObject("SELECT id FROM orders WHERE customer_id=?", Long.class, b);
        jdbc.update("INSERT INTO users(username,password_hash,role) VALUES ('order_empty',?,'CUSTOMER')", encoder.encode(emptyPassword));
        // A 一共 22 单，跨越默认页；仅隔离库构造两个不同的模拟标记。
        long model = jdbc.queryForObject("SELECT model_id FROM orders WHERE id=?", Long.class, orderA);
        jdbc.update("UPDATE device_models SET is_demo=false WHERE id=?", model);
        for (int i = 0; i < 21; i++) jdbc.update("INSERT INTO orders(order_no,customer_id,model_id,serial_no,purchase_date,is_demo) VALUES(?,?,?,?,?,true)",
            "ORDER-IT-"+i, a, model, "SIM-"+i, java.sql.Date.valueOf("2026-01-02"));
    }

    class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
            if (token != null) builder.header("X-CSRF-TOKEN", token);
            if (body != null) builder.header("Content-Type", "application/x-www-form-urlencoded");
            return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return request("GET", path, null, null); }
        String csrf() throws Exception { return json.readTree(get("/api/auth/csrf").body()).get("token").asString(); }
    }
    Browser login(String name, String password) throws Exception {
        var browser = new Browser();
        assertEquals(200, browser.request("POST", "/api/auth/login", "username="+name+"&password="+URLEncoder.encode(password, StandardCharsets.UTF_8), browser.csrf()).statusCode());
        return browser;
    }
    Browser customerA() throws Exception { return login("demo_customer_a", passwords.customerA()); }
    Browser customerB() throws Exception { return login("demo_customer_b", passwords.customerB()); }
    JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains("password"));
        return json.readTree(response.body());
    }
    JsonNode error(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        var body = json.readTree(response.body());
        assertEquals(Set.of("code","message","traceId"), body.propertyNames());
        assertEquals(code, body.get("code").asString());
        assertFalse(body.get("traceId").asString().isBlank());
        assertEquals(body.get("traceId").asString(), response.headers().firstValue("X-Trace-Id").orElseThrow());
        assertFalse(response.body().contains("SELECT"));
        assertFalse(response.body().contains("password"));
        return body;
    }

    @Test void anonymousAndInvalidSessionCannotReadEitherEndpoint() throws Exception {
        for (String path : List.of("/api/orders", "/api/orders/"+orderA)) {
            error(new Browser().get(path), 401, "UNAUTHENTICATED");
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Cookie","JSESSIONID=invalid").build(), HttpResponse.BodyHandlers.ofString());
            error(response,401,"UNAUTHENTICATED");
        }
    }

    @Test void listsAndDetailsOnlyBelongToCurrentCustomer() throws Exception {
        var first = ok(customerA().get("/api/orders?size=100"));
        assertEquals(22, first.get("total").asInt());
        for (var row : first.get("items")) assertNotEquals(orderB, row.get("id").asLong());
        var second = ok(customerB().get("/api/orders"));
        assertEquals(1, second.get("total").asInt());
        assertEquals(orderB, second.get("items").get(0).get("id").asLong());
        assertEquals(orderA, ok(customerA().get("/api/orders/"+orderA)).get("id").asLong());
        assertEquals(orderB, ok(customerB().get("/api/orders/"+orderB)).get("id").asLong());
    }

    @Test void missingAndForeignOrdersHaveSame404ContractBothDirections() throws Exception {
        for (boolean asA : List.of(true,false)) {
            var browser = asA ? customerA() : customerB();
            var foreign = error(browser.get("/api/orders/"+(asA ? orderB : orderA)),404,"ORDER_NOT_FOUND");
            var missing = error(browser.get("/api/orders/9223372036854775807"),404,"ORDER_NOT_FOUND");
            assertEquals(foreign.get("message"),missing.get("message"));
        }
    }

    @Test void forgedIdentityParametersNeverExpandScope() throws Exception {
        for (String params : List.of("userId="+b,"customerId="+b,"username=demo_customer_b","role=STAFF","userId="+b+"&customerId="+b+"&username=demo_staff&role=STAFF")) {
            var browser = customerA();
            assertEquals(22,ok(browser.get("/api/orders?"+params)).get("total").asInt());
            error(browser.get("/api/orders/"+orderB+"?"+params),404,"ORDER_NOT_FOUND");
        }
    }

    @Test void staffIsDeniedBeforeOrderLookup() throws Exception {
        var browser = login("demo_staff",passwords.staff());
        for (String path : List.of("/api/orders","/api/orders/"+orderA,"/api/orders/not-a-number")) error(browser.get(path),403,"FORBIDDEN");
    }

    @Test void implicitHeadMappingCannotBypassCustomerRoleOrOwnership() throws Exception {
        var customer = customerA();
        assertEquals(200,customer.request("HEAD","/api/orders/"+orderA,null,null).statusCode());
        assertEquals(404,customer.request("HEAD","/api/orders/"+orderB,null,null).statusCode());
        assertEquals(403,login("demo_staff",passwords.staff()).request("HEAD","/api/orders/"+orderA,null,null).statusCode());
        assertEquals(401,new Browser().request("HEAD","/api/orders",null,null).statusCode());
    }

    @Test void responseIsAnExactWhitelistWithIndependentDemoFlagsAndDate() throws Exception {
        var row = ok(customerA().get("/api/orders/"+orderA));
        assertEquals(Set.of("id","orderNo","modelCode","brand","serialNo","purchaseDate","orderDemo","modelDemo"),row.propertyNames());
        var expected = jdbc.queryForMap("SELECT order_no,serial_no,purchase_date FROM orders WHERE id=?",orderA);
        assertEquals(expected.get("order_no"),row.get("orderNo").asString());
        assertEquals(expected.get("serial_no"),row.get("serialNo").asString());
        assertEquals(expected.get("purchase_date").toString(),row.get("purchaseDate").asString());
        assertEquals("Brother",row.get("brand").asString());
        assertEquals("HL-L2350DW",row.get("modelCode").asString());
        assertTrue(row.get("orderDemo").asBoolean());
        assertFalse(row.get("modelDemo").asBoolean());
    }

    @Test void paginationDefaultsBoundariesOrderAndEmptyPages() throws Exception {
        var browser = customerA();
        var first = ok(browser.get("/api/orders"));
        assertEquals(Set.of("items","page","size","total","totalPages"),first.propertyNames());
        assertEquals(1,first.get("page").asInt()); assertEquals(20,first.get("size").asInt());
        assertEquals(20,first.get("items").size());assertEquals(2,first.get("totalPages").asInt());
        long previous = Long.MAX_VALUE;
        for (var row : first.get("items")) { assertTrue(row.get("id").asLong()<previous); previous=row.get("id").asLong(); }
        var second = ok(browser.get("/api/orders?page=2"));
        assertEquals(2,second.get("items").size());
        assertTrue(second.get("items").get(0).get("id").asLong()<previous);
        assertEquals(22,ok(browser.get("/api/orders?size=100")).get("items").size());
        assertEquals(1,ok(browser.get("/api/orders?size=1")).get("items").size());
        for (String page : List.of("3","2147483647")) assertEquals(0,ok(browser.get("/api/orders?page="+page)).get("items").size());
    }

    @Test void customerWithoutOrdersHasNormalEmptyPage() throws Exception {
        var page = ok(login("order_empty",emptyPassword).get("/api/orders"));
        assertEquals(0,page.get("items").size());assertEquals(0,page.get("total").asLong());assertEquals(0,page.get("totalPages").asLong());
    }

    @Test void invalidIdsAndPagingAre400() throws Exception {
        var browser = customerA();
        for (String id : List.of("abc","0","-1","1.5","9223372036854775808","999999999999999999999999999")) error(browser.get("/api/orders/"+id),400,"INVALID_ARGUMENT");
        for (String query : List.of("page=0","page=-1","page=","page=abc","page=1.5","page=2147483648","page=1&page=2","size=0","size=-1","size=101","size=","size=abc","size=9223372036854775808","size=1&size=2")) error(browser.get("/api/orders?"+query),400,"INVALID_ARGUMENT");
    }

    @Test void logoutRevokesReadsAndStillRequiresFreshCsrf() throws Exception {
        var browser = customerA();
        error(browser.request("POST","/api/auth/logout","",null),403,"CSRF_INVALID");
        assertEquals(200,browser.get("/api/orders").statusCode());
        assertEquals(200,browser.request("POST","/api/auth/logout","",browser.csrf()).statusCode());
        error(browser.get("/api/orders"),401,"UNAUTHENTICATED");
        error(browser.get("/api/orders/"+orderA),401,"UNAUTHENTICATED");
    }
}

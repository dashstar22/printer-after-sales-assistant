package local.printer.auth;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import local.printer.seed.DemoSeedService;
import local.printer.seed.SeedPasswords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Tomcat、MySQL、认证过滤链、Cookie；不使用 MockMvc 或内存用户。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"AI_ENABLED=false", "AI_API_KEY=", "AI_CHAT_MODEL=", "QDRANT_URL=http://127.0.0.1:9"})
@Import(AuthenticationIT.TestEndpoints.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    private final ObjectMapper json = new ObjectMapper();
    private final SeedPasswords passwords = new SeedPasswords(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());

    @BeforeAll void prepareIsolatedRealDatabase() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("printer_auth_it_"), "只允许在独立认证测试库执行");
        assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8.4."));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("root@"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        seed.seed(passwords);
    }

    class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String token;
        HttpResponse<String> request(String method, String path, String body, String csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            if (method.equals("POST")) builder.header("Content-Type", "application/x-www-form-urlencoded");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
        String csrf() throws Exception {
            var response = request("GET", "/api/auth/csrf", null, null);
            assertEquals(200, response.statusCode());
            token = json.readTree(response.body()).get("token").asString();
            return token;
        }
        String sessionId() {
            return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        }
        HttpResponse<String> login(String username, String password) throws Exception {
            if (token == null) csrf();
            return request("POST", "/api/auth/login", "username=" + encode(username) + "&password=" + encode(password), token);
        }
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        JsonNode body = json.readTree(response.body());
        assertEquals(code, body.get("code").asString());
        assertFalse(body.get("traceId").asString().isBlank());
        assertEquals(3, body.size());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"));
        assertFalse(response.body().contains("password"));
    }

    @Test void unauthenticatedAndInvalidSessionAre401() throws Exception {
        var browser = new Browser();
        error(browser.request("GET", "/api/auth/me", null, null), 401, "UNAUTHENTICATED");
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/me"))
                .header("Cookie", "JSESSIONID=invalid-session").build(), HttpResponse.BodyHandlers.ofString());
        error(response, 401, "UNAUTHENTICATED");
    }

    @Test void allThreeRealAccountsAuthenticateWithCorrectIdentity() throws Exception {
        var raw = new String[] {passwords.customerA(), passwords.customerB(), passwords.staff()};
        for (int i = 0; i < 3; i++) {
            var browser = new Browser();
            var response = browser.login(DemoSeedService.USERS.get(i), raw[i]);
            assertEquals(200, response.statusCode());
            JsonNode identity = json.readTree(response.body());
            assertEquals(DemoSeedService.USERS.get(i), identity.get("username").asString());
            assertEquals(i == 2 ? "STAFF" : "CUSTOMER", identity.get("role").asString());
            assertEquals(3, identity.size());
            assertEquals(200, browser.request("GET", "/api/auth/me", null, null).statusCode());
        }
    }

    @Test void incorrectAndUnknownCredentialsFailUniformly() throws Exception {
        for (String username : new String[] {"unknown-account", "demo_customer_a"}) {
            var browser = new Browser();
            error(browser.login(username, "incorrect-credential"), 401, "AUTHENTICATION_FAILED");
            error(browser.request("GET", "/api/auth/me", null, null), 401, "UNAUTHENTICATED");
        }
    }

    @Test void loginAndLogoutRejectMissingAndIncorrectCsrf() throws Exception {
        var browser = new Browser();
        browser.csrf();
        String form = "username=demo_customer_a&password=" + encode(passwords.customerA());
        error(browser.request("POST", "/api/auth/login", form, null), 403, "CSRF_INVALID");
        error(browser.request("POST", "/api/auth/login", form, "invalid"), 403, "CSRF_INVALID");
        assertEquals(200, browser.login("demo_customer_a", passwords.customerA()).statusCode());
        error(browser.request("POST", "/api/auth/logout", "", null), 403, "CSRF_INVALID");
        error(browser.request("POST", "/api/auth/logout", "", "invalid"), 403, "CSRF_INVALID");
        assertEquals(200, browser.request("GET", "/api/auth/me", null, null).statusCode());
    }

    @Test void sessionFixationProtectionAndCsrfRotation() throws Exception {
        var browser = new Browser();
        String oldToken = browser.csrf();
        String oldId = browser.sessionId();
        assertEquals(200, browser.login("demo_customer_a", passwords.customerA()).statusCode());
        assertFalse(oldId.equals(browser.sessionId()));
        error(browser.request("POST", "/api/auth/logout", "", oldToken), 403, "CSRF_INVALID");
        String newToken = browser.csrf();
        assertFalse(oldToken.equals(newToken));
        assertEquals(200, browser.request("POST", "/api/auth/logout", "", newToken).statusCode());
    }

    @Test void logoutInvalidatesOriginalCookieAndPreviousCsrf() throws Exception {
        var browser = new Browser();
        browser.login("demo_customer_a", passwords.customerA());
        String token = browser.csrf();
        String originalCookie = browser.sessionId();
        assertEquals(200, browser.request("POST", "/api/auth/logout", "", token).statusCode());
        error(browser.request("GET", "/api/auth/me", null, null), 401, "UNAUTHENTICATED");
        var old = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/me"))
                .header("Cookie", "JSESSIONID="+originalCookie).build(), HttpResponse.BodyHandlers.ofString());
        error(old, 401, "UNAUTHENTICATED");
        error(browser.request("POST", "/api/auth/login", "username=demo_customer_a&password="+encode(passwords.customerA()), token), 403, "CSRF_INVALID");
        browser.csrf();
        assertEquals(200, browser.login("demo_customer_a", passwords.customerA()).statusCode());
    }

    @Test void customerCannotForgeIdentityOrAccessStaffRoutes() throws Exception {
        var customer = new Browser();
        customer.csrf();
        assertEquals(200, customer.request("POST", "/api/auth/login", "username=demo_customer_a&password="+encode(passwords.customerA())+"&role=STAFF&userId=999", customer.token).statusCode());
        var me = customer.request("GET", "/api/auth/me?userId=999&username=demo_staff&role=STAFF", null, null);
        assertEquals("demo_customer_a", json.readTree(me.body()).get("username").asString());
        assertEquals("CUSTOMER", json.readTree(me.body()).get("role").asString());
        error(customer.request("GET", "/api/staff/auth-probe", null, null), 403, "FORBIDDEN");
        customer.csrf();
        error(customer.request("POST", "/api/tickets/test-only/accept", "role=STAFF", customer.token), 403, "FORBIDDEN");
        var staff = new Browser(); staff.login("demo_staff", passwords.staff());
        assertEquals(200, staff.request("GET", "/api/staff/auth-probe", null, null).statusCode());
        staff.csrf();
        assertEquals(200, staff.request("POST", "/api/tickets/test-only/accept", "", staff.token).statusCode());
    }

    @Test void realSessionTimeoutRejectsPreviouslyAuthenticatedCookie() throws Exception {
        var browser = new Browser(); browser.login("demo_customer_a", passwords.customerA()); browser.csrf();
        assertEquals(200, browser.request("POST", "/api/test/session/expire-soon", "", browser.token).statusCode());
        Thread.sleep(2200);
        error(browser.request("GET", "/api/auth/me", null, null), 401, "UNAUTHENTICATED");
    }

    @Test void sessionCookieFlagsAndNoDefaultLoginPage() throws Exception {
        var browser = new Browser();
        var response = browser.request("GET", "/api/auth/csrf", null, null);
        String cookie = response.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertFalse(cookie.contains("Secure"));
        error(browser.request("GET", "/login", null, null), 401, "UNAUTHENTICATED");
    }

    @Test void sameOriginEntryAssetsArePublicButIdentityIsProtected() throws Exception {
        var browser = new Browser();
        assertEquals(302, browser.request("GET", "/app", null, null).statusCode());
        for (String path : new String[] {"/app/", "/app/index.html", "/app/app.js", "/app/api.js", "/app/state.js", "/app/style.css",
                "/app/atmosphere.js", "/app/views/login.js", "/app/views/customer.js", "/app/views/staff.js", "/app/views/status.js", "/app/views/shared.js",
                "/app/assets/brand.svg", "/app/assets/icons/pause.svg", "/app/vendor/motion-14.9.0.js", "/app/vendor/tsparticles-basic-4.5.0.js"}) {
            var get = browser.request("GET", path, null, null);
            assertEquals(200, get.statusCode(), path);
            assertFalse(get.body().isBlank(), path);
            assertFalse(get.body().contains("password_hash"), path);
            assertEquals(200, browser.request("HEAD", path, null, null).statusCode(), path);
        }
        error(browser.request("GET", "/api/auth/me", null, null), 401, "UNAUTHENTICATED");
        var head = browser.request("HEAD", "/api/staff/auth-probe", null, null);
        assertEquals(401, head.statusCode());
        assertTrue(head.body().isEmpty());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEndpoints {
        @Bean ProbeController probeController() { return new ProbeController(); }
    }
    @RestController
    static class ProbeController {
        @GetMapping("/api/staff/auth-probe") Map<String, Boolean> staff() { return Map.of("allowed", true); }
        @PostMapping("/api/tickets/test-only/accept") Map<String, Boolean> write() { return Map.of("allowed", true); }
        @PostMapping("/api/test/session/expire-soon") Map<String, Boolean> expire(HttpServletRequest request) {
            request.getSession(false).setMaxInactiveInterval(1);
            return Map.of("timeoutChanged", true);
        }
    }
}

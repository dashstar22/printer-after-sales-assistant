package local.printer.auth;

import java.net.*;
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
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real servlet filters, persistent SQL token store and HTTP cookies. No mocked identity. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"AI_ENABLED=false","AI_API_KEY=","AI_CHAT_MODEL=","QDRANT_URL=http://127.0.0.1:9"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistentLoginIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    final ObjectMapper json=new ObjectMapper();
    final SeedPasswords passwords=new SeedPasswords(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());
    @BeforeAll void setup(){
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_remember_it_"));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"));
        seed.seed(passwords);
    }
    @BeforeEach void revokeTestTokens(){jdbc.update("DELETE FROM persistent_logins");}
    final class Browser {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> request(String method,String path,String body,String csrf)throws Exception{
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
            if(csrf!=null)builder.header("X-CSRF-TOKEN",csrf);
            if(method.equals("POST"))builder.header("Content-Type","application/x-www-form-urlencoded");
            return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        String csrf()throws Exception{return json.readTree(request("GET","/api/auth/csrf",null,null).body()).get("token").asString();}
        HttpResponse<String> login(boolean remember)throws Exception{
            return request("POST","/api/auth/login","username=demo_customer_a&password="+URLEncoder.encode(passwords.customerA(),StandardCharsets.UTF_8)+"&remember-me="+remember,csrf());
        }
        String remember(){return cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("PRINTER_REMEMBER")&&!c.hasExpired()).findFirst().orElseThrow().getValue();}
        void forgetSession(){for(var uri:cookies.getCookieStore().getURIs())for(var cookie:List.copyOf(cookies.getCookieStore().get(uri)))if(cookie.getName().equals("JSESSIONID"))cookies.getCookieStore().remove(uri,cookie);}
    }
    HttpResponse<String> cookieRequest(String cookie)throws Exception{
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/me")).header("Cookie","PRINTER_REMEMBER="+cookie).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void optOutCreatesOnlySession()throws Exception{
        var b=new Browser();assertEquals(200,b.login(false).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins",Integer.class));
        b.forgetSession();assertEquals(401,b.request("GET","/api/auth/me",null,null).statusCode());
    }
    @Test void optInUsesOpaqueHttpOnlySameSiteCookieAndRotatesIt()throws Exception{
        var b=new Browser();var login=b.login(true);assertEquals(200,login.statusCode());
        String header=login.headers().allValues("set-cookie").stream().filter(c->c.startsWith("PRINTER_REMEMBER=")).findFirst().orElseThrow();
        assertTrue(header.contains("HttpOnly"));assertTrue(header.contains("SameSite=Lax"));assertTrue(header.contains("Max-Age=1209600"));
        assertFalse(header.contains(passwords.customerA()));assertFalse(header.contains("demo_customer_a"));
        String old=b.remember();b.forgetSession();var restored=b.request("GET","/api/auth/me",null,null);
        assertEquals(200,restored.statusCode());assertEquals("demo_customer_a",json.readTree(restored.body()).get("username").asString());
        assertNotEquals(old,b.remember());assertEquals(3,json.readTree(restored.body()).size());
        // Spring asks remembered users to sign in again on a forbidden privileged route.
        var denied=b.request("GET","/api/staff/auth-probe?role=STAFF&userId=999",null,null);
        assertEquals(401,denied.statusCode());assertEquals("UNAUTHENTICATED",json.readTree(denied.body()).get("code").asString());
    }
    @Test void logoutRevokesServerTokensAndOldCookie()throws Exception{
        var b=new Browser();b.login(true);String old=b.remember();
        assertEquals(200,b.request("POST","/api/auth/logout","",b.csrf()).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins",Integer.class));
        assertEquals(401,cookieRequest(old).statusCode());
    }
    @Test void expiredCookieCannotRestoreIdentity()throws Exception{
        var b=new Browser();b.login(true);String old=b.remember();
        jdbc.update("UPDATE persistent_logins SET last_used=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 15 DAY)");
        assertEquals(401,cookieRequest(old).statusCode());
    }
    @Test void forgedAndReplayedTokensAreRejectedWithoutRoleEscalation()throws Exception{
        assertEquals(401,cookieRequest("forged-role-STAFF").statusCode());
        var b=new Browser();b.login(true);String old=b.remember();b.forgetSession();
        assertEquals(200,b.request("GET","/api/auth/me?username=demo_staff&role=STAFF",null,null).statusCode());
        var replay=cookieRequest(old);assertEquals(401,replay.statusCode());assertEquals("UNAUTHENTICATED",json.readTree(replay.body()).get("code").asString());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins",Integer.class));
    }
    @Test void rememberedIdentityStillRequiresCsrfForWrites()throws Exception{
        var b=new Browser();b.login(true);b.forgetSession();assertEquals(200,b.request("GET","/api/auth/me",null,null).statusCode());
        var response=b.request("POST","/api/auth/logout","",null);assertEquals(403,response.statusCode());assertEquals("CSRF_INVALID",json.readTree(response.body()).get("code").asString());
    }
}

package local.printer.auth;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import local.printer.seed.DemoSeedService;
import local.printer.seed.SeedPasswords;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"AI_ENABLED=false","AI_API_KEY=","AI_CHAT_MODEL=","QDRANT_URL=http://127.0.0.1:9"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    @Autowired PasswordEncoder encoder;
    final ObjectMapper json=new ObjectMapper();
    final SeedPasswords seedPasswords=new SeedPasswords(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());
    @BeforeAll void setup(){
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_registration_it_"));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"));
        seed.seed(seedPasswords);
    }
    String name(){return "reg_"+UUID.randomUUID().toString().replace("-","").substring(0,16);}
    String password(){return UUID.randomUUID().toString();}
    Map<String,String> input(String username,String password){return Map.of("username",username,"password",password,"passwordConfirmation",password);}
    class Browser {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        String token;
        HttpResponse<String> request(String method,String path,String body,String contentType)throws Exception{
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
            if(token!=null)builder.header("X-CSRF-TOKEN",token);
            if(contentType!=null)builder.header("Content-Type",contentType);
            return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        void csrf()throws Exception{token=json.readTree(request("GET","/api/auth/csrf",null,null).body()).get("token").asString();}
        HttpResponse<String> register(Object body)throws Exception{return request("POST","/api/auth/register",json.writeValueAsString(body),"application/json");}
        HttpResponse<String> login(String username,String password,boolean remember)throws Exception{
            csrf();return request("POST","/api/auth/login","username="+URLEncoder.encode(username,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8)+"&remember-me="+remember,"application/x-www-form-urlencoded");
        }
        HttpResponse<String> me()throws Exception{return request("GET","/api/auth/me",null,null);}
    }
    Browser browser()throws Exception{var b=new Browser();b.csrf();return b;}
    JsonNode error(HttpResponse<String> response,int status,String code){
        assertEquals(status,response.statusCode());var body=json.readTree(response.body());
        assertEquals(code,body.get("code").asString());assertFalse(body.get("traceId").asString().isBlank());return body;
    }
    @Test void customerRegistersHashesPasswordAndCanLoginWithoutInheritedOrders()throws Exception{
        var b=browser();String user=name(),raw=password();var result=b.register(input(user,raw));
        assertEquals(201,result.statusCode());assertTrue(json.readTree(result.body()).get("registered").asBoolean());
        assertEquals("no-store",result.headers().firstValue("cache-control").orElseThrow());
        assertFalse(result.body().contains(raw));assertEquals(401,b.me().statusCode());
        String hash=jdbc.queryForObject("SELECT password_hash FROM users WHERE username=?",String.class,user);
        assertNotEquals(raw,hash);assertTrue(hash.startsWith("{bcrypt}"));assertTrue(encoder.matches(raw,hash));
        assertEquals("CUSTOMER",jdbc.queryForObject("SELECT role FROM users WHERE username=?",String.class,user));
        assertEquals(200,b.login(user,raw,false).statusCode());assertEquals("CUSTOMER",json.readTree(b.me().body()).get("role").asString());
        assertEquals(0,json.readTree(b.request("GET","/api/orders",null,null).body()).get("total").asInt());
        error(b.request("GET","/api/staff/tickets",null,null),403,"FORBIDDEN");
    }
    @Test void duplicateRegistrationNeverChangesExistingPasswordOrRole()throws Exception{
        var b=browser();String hash=jdbc.queryForObject("SELECT password_hash FROM users WHERE username='demo_staff'",String.class);
        error(b.register(input("demo_staff",password())),409,"ACCOUNT_EXISTS");
        assertEquals(hash,jdbc.queryForObject("SELECT password_hash FROM users WHERE username='demo_staff'",String.class));
        assertEquals("STAFF",jdbc.queryForObject("SELECT role FROM users WHERE username='demo_staff'",String.class));
    }
    @Test void accountNamesNormalizeAndCaseVariantsCannotDuplicate()throws Exception{
        var b=browser();String user=name();assertEquals(201,b.register(input("  "+user.toUpperCase(Locale.ROOT)+"  ",password())).statusCode());
        error(b.register(input(user,password())),409,"ACCOUNT_EXISTS");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?",Integer.class,user));
    }
    @Test void concurrentDuplicatesCreateExactlyOneAccount()throws Exception{
        var first=browser();var second=browser();String user=name(),raw=password();
        var pool=Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->first.register(input(user,raw)).statusCode());
            var b=pool.submit(()->second.register(input(user,raw)).statusCode());
            var statuses=new ArrayList<>(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));Collections.sort(statuses);
            assertEquals(List.of(201,409),statuses);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?",Integer.class,user));
        }finally{pool.shutdownNow();}
    }
    @Test void registrationRequiresValidCsrf()throws Exception{
        var b=new Browser();String user=name();error(b.register(input(user,password())),403,"CSRF_INVALID");
        b.csrf();b.token="forged";error(b.register(input(user,password())),403,"CSRF_INVALID");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?",Integer.class,user));
    }
    @Test void invalidNamesAndLengthsAreRejected()throws Exception{
        var b=browser();for(String user:List.of("abc","a".repeat(33),"bad name","用户账号","<script>"))error(b.register(input(user,password())),400,"REGISTRATION_INVALID");
        assertEquals(201,b.register(input(name(),"a".repeat(64))).statusCode());
    }
    @Test void invalidPasswordsAndMismatchNeverCreateAccount()throws Exception{
        var b=browser();String user=name();for(String raw:List.of("short"," ".repeat(12),"a".repeat(65),"密".repeat(25),"password\nvalue"))error(b.register(input(user,raw)),400,"REGISTRATION_INVALID");
        error(b.register(Map.of("username",user,"password",password(),"passwordConfirmation",password())),400,"REGISTRATION_INVALID");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?",Integer.class,user));
    }
    @Test void forgedRoleAndIdentityFieldsAreRejected()throws Exception{
        var b=browser();String user=name();for(String key:List.of("role","id","userId","customerId")){
            var payload=new HashMap<String,Object>(input(user,password()));payload.put(key,"STAFF");
            error(b.register(payload),400,"REGISTRATION_INVALID");
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?",Integer.class,user));
    }
    @Test void invalidJsonTypesAndContentTypeReturnStableErrors()throws Exception{
        var b=browser();error(b.request("POST","/api/auth/register","{","application/json"),400,"REGISTRATION_INVALID");
        error(b.register(List.of("invalid")),400,"REGISTRATION_INVALID");
        error(b.register(Map.of("username",123,"password",password(),"passwordConfirmation",password())),400,"REGISTRATION_INVALID");
        error(b.request("POST","/api/auth/register","username=invalid","application/x-www-form-urlencoded"),415,"UNSUPPORTED_MEDIA_TYPE");
    }
    @Test void loggedInAccountCannotRegisterAndSwitchIdentity()throws Exception{
        var b=browser();assertEquals(200,b.login("demo_customer_a",seedPasswords.customerA(),false).statusCode());b.csrf();
        String user=name();error(b.register(input(user,password())),409,"ALREADY_SIGNED_IN");
        assertEquals("demo_customer_a",json.readTree(b.me().body()).get("username").asString());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?",Integer.class,user));
    }
    @Test void registeredCustomerCanOptIntoPersistentLogin()throws Exception{
        var b=browser();String user=name(),raw=password();assertEquals(201,b.register(input(user,raw)).statusCode());
        assertEquals(200,b.login(user,raw,true).statusCode());
        assertTrue(b.cookies.getCookieStore().getCookies().stream().anyMatch(c->c.getName().equals("PRINTER_REMEMBER")&&c.isHttpOnly()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins WHERE username=?",Integer.class,user));
    }
}

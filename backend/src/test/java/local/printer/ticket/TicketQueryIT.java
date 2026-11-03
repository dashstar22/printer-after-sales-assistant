package local.printer.ticket;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import local.printer.auth.SessionUser;
import local.printer.seed.DemoSeedService;
import local.printer.seed.SeedPasswords;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL and HTTP; committed fixtures are retained in the dedicated query schema. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"AI_ENABLED=false","AI_API_KEY=","AI_CHAT_MODEL=","QDRANT_URL=http://127.0.0.1:9"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TicketQueryIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    @Autowired PasswordEncoder encoder;
    @Autowired TicketQueryService service;
    @Autowired TicketQueryMapper mapper;
    final ObjectMapper json=new ObjectMapper();
    final SeedPasswords passwords=new SeedPasswords(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());
    final String usernameA="query_a_"+UUID.randomUUID(), usernameB="query_b_"+UUID.randomUUID(), usernameStaff="query_staff_"+UUID.randomUUID();
    final String emptyUser="query_empty_"+UUID.randomUUID(), group="Q-"+UUID.randomUUID();
    long a,b,staff,orderA,orderB;
    List<Long> aIds=new ArrayList<>(), bIds=new ArrayList<>();
    final String special="卡纸 50%_!\\' 🖨";

    @BeforeAll void fixturesThroughRealWorkflow() throws Exception {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_query_it_"));
        assertEquals("8.4.13",jdbc.queryForObject("SELECT VERSION()",String.class));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"));
        seed.seed(passwords);
        for(var row:List.of(List.of(usernameA,passwords.customerA(),"CUSTOMER"),List.of(usernameB,passwords.customerB(),"CUSTOMER"),
            List.of(usernameStaff,passwords.staff(),"STAFF"),List.of(emptyUser,passwords.customerA(),"CUSTOMER")))
            jdbc.update("INSERT INTO users(username,password_hash,role) VALUES(?,?,?)",row.get(0),encoder.encode(row.get(1)),row.get(2));
        a=userId(usernameA); b=userId(usernameB); staff=userId(usernameStaff);
        long model=jdbc.queryForObject("SELECT id FROM device_models WHERE model_code='HL-L2350DW'",Long.class);
        orderA=order(a,model,group+"-A");orderB=order(b,model,group+"-B");
        var ca=customerA();var cb=customerB();
        for(String symptom:List.of(special,"普通卡纸","离线","关闭卡纸","空白打印")) aIds.add(create(ca,orderA,symptom));
        for(String symptom:List.of("卡纸 50%_!","他人故障")) bIds.add(create(cb,orderB,symptom));
        // Read fixtures only: T016 transition API is intentionally absent.
        jdbc.update("UPDATE tickets SET status='IN_PROGRESS',version=1,accepted_by=? WHERE id=?",staff,aIds.get(2));
        jdbc.update("UPDATE tickets SET status='CLOSED',version=2,accepted_by=? WHERE id=?",staff,aIds.get(3));
        long target=aIds.get(3);
        var time=LocalDateTime.parse("2026-10-28T01:02:03.123456");
        // Keep all four fixture times deterministic; real wall-clock CREATED can otherwise sort after these fixed NOTE events.
        jdbc.update("UPDATE ticket_events SET created_at=? WHERE ticket_id=? AND event_type='CREATED'",time.minusSeconds(2),target);
        jdbc.update("INSERT INTO ticket_events(ticket_id,actor_id,event_type,note,created_at) VALUES(?,?,'NOTE','同时间先插入',?)",target,staff,time);
        jdbc.update("INSERT INTO ticket_events(ticket_id,actor_id,event_type,note,created_at) VALUES(?,?,'CLOSED','隔离读取夹具',?)",target,staff,time);
        jdbc.update("INSERT INTO ticket_events(ticket_id,actor_id,event_type,note,created_at) VALUES(?,?,'ACCEPTED','更早时间后插入',?)",target,staff,LocalDateTime.parse("2026-10-28T01:02:02.123456"));
    }
    long userId(String name) { return jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name); }
    long order(long owner,long model,String no) {
        jdbc.update("INSERT INTO orders(order_no,customer_id,model_id,serial_no,purchase_date,is_demo) VALUES(?,?,?,'DEMO-QUERY','2026-10-27',true)",no,owner,model);
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

    String query(String keyword) { return "/api/tickets?keyword="+URLEncoder.encode(keyword,StandardCharsets.UTF_8); }
    JsonNode ok(HttpResponse<String> r) throws Exception {
        assertEquals(200,r.statusCode(),r.body());assertEquals("no-store",r.headers().firstValue("Cache-Control").orElseThrow());
        return json.readTree(r.body());
    }
    JsonNode error(HttpResponse<String> r,int status,String code) throws Exception {
        assertEquals(status,r.statusCode(),r.body());var body=json.readTree(r.body());
        assertEquals(Set.of("code","message","traceId"),body.propertyNames());assertEquals(code,body.get("code").asString());
        assertEquals(body.get("traceId").asString(),r.headers().firstValue("X-Trace-Id").orElseThrow());
        assertEquals("no-store",r.headers().firstValue("Cache-Control").orElseThrow());return body;
    }
    long create(Browser browser,long order,String symptom) throws Exception {
        var input=Map.of("orderId",order,"symptom",symptom,"extra","已取纸\n仍报错","contact","模拟联系人","contactMethod","测试方式待确认");
        var draft=browser.request("POST","/api/repair-drafts",input);assertEquals(201,draft.statusCode());
        long id=json.readTree(draft.body()).get("id").asLong();
        var cr=browser.request("POST","/api/repair-drafts/"+id+"/confirmations",Map.of("confirmed",true,"expectedVersion",0));
        assertEquals(201,cr.statusCode(),cr.body());var confirmation=json.readTree(cr.body());
        var r=browser.request("POST","/api/ticket-operations/"+confirmation.get("operationId").asString()+"/execute",
            Map.of("confirmationId",confirmation.get("confirmationId").asString(),"draftId",id,"expectedVersion",0));
        assertEquals(201,r.statusCode(),r.body());return json.readTree(r.body()).get("ticket").get("id").asLong();
    }
    List<Long> ids(JsonNode page) {
        var list=new ArrayList<Long>();for(var item:page.get("items"))list.add(item.get("id").asLong());return list;
    }
    List<Long> reversed(List<Long> list) {var result=new ArrayList<>(list);Collections.reverse(result);return result;}
    String rows() {
        var text=new StringBuilder();for(String table:List.of("repair_drafts","repair_confirmations","tickets","ticket_events"))
            text.append(jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id").toString());return text.toString();
    }

    @Test void workflowListAndDetailRoundTrip() throws Exception {
        var browser=customerA();var page=ok(browser.request("GET","/api/tickets",null));
        assertEquals(Set.of("items","page","size","total","totalPages"),page.propertyNames());
        assertEquals(1,page.get("page").asInt());assertEquals(20,page.get("size").asInt());assertEquals(5,page.get("total").asInt());
        assertEquals(reversed(aIds),ids(page));
        assertEquals(Set.of("id","ticketNo","orderId","orderNo","modelCode","status","version"),page.get("items").get(0).propertyNames());
        var detail=ok(browser.request("GET","/api/tickets/"+aIds.get(0),null));
        assertEquals(Set.of("id","ticketNo","orderId","status","version","acceptedBy","snapshotSymptom","snapshotExtra","snapshotContact","snapshotContactMethod","order","events"),detail.propertyNames());
        assertEquals(special,detail.get("snapshotSymptom").asString());assertEquals("已取纸\n仍报错",detail.get("snapshotExtra").asString());
        assertEquals("模拟联系人",detail.get("snapshotContact").asString());assertEquals("测试方式待确认",detail.get("snapshotContactMethod").asString());
        assertEquals("PENDING",detail.get("status").asString());assertEquals(0,detail.get("version").asLong());assertTrue(detail.get("acceptedBy").isNull());
        assertEquals(orderA,detail.get("orderId").asLong());assertTrue(detail.get("order").get("orderDemo").asBoolean());
        assertTrue(detail.get("order").get("modelDemo").asBoolean());assertEquals("HL-L2350DW",detail.get("order").get("modelCode").asString());
        assertEquals("CREATED",detail.get("events").get(0).get("eventType").asString());assertEquals(a,detail.get("events").get(0).get("actorId").asLong());
    }
    @Test void bothCustomersAndUniformNotFound() throws Exception {
        var ca=customerA();var cb=customerB();assertEquals(reversed(bIds),ids(ok(cb.request("GET","/api/tickets",null))));
        var foreign=error(ca.request("GET","/api/tickets/"+bIds.get(0),null),404,"TICKET_NOT_FOUND");
        var missing=error(ca.request("GET","/api/tickets/9223372036854775807",null),404,"TICKET_NOT_FOUND");
        assertEquals(foreign.get("message").asString(),missing.get("message").asString());
        error(cb.request("GET","/api/tickets/"+aIds.get(0),null),404,"TICKET_NOT_FOUND");
        assertEquals(0,ok(ca.request("GET",query("他人故障"),null)).get("total").asInt());
    }
    @Test void staffEnterpriseScopeAndOperationRestriction() throws Exception {
        var browser=login(usernameStaff,passwords.staff());var page=ok(browser.request("GET",query(group),null));
        var expected=new ArrayList<>(aIds);expected.addAll(bIds);assertEquals(reversed(expected),ids(page));assertEquals(7,page.get("total").asInt());
        ok(browser.request("GET","/api/tickets/"+aIds.get(0),null));ok(browser.request("GET","/api/tickets/"+bIds.get(0),null));
        error(browser.request("GET","/api/tickets/9223372036854775807",null),404,"TICKET_NOT_FOUND");
        error(browser.request("GET","/api/ticket-operations/"+UUID.randomUUID(),null),403,"FORBIDDEN");
    }
    @Test void anonymousListAndDetailDenied() throws Exception {
        var browser=new Browser();error(browser.request("GET","/api/tickets",null),401,"UNAUTHENTICATED");
        error(browser.request("GET","/api/tickets/"+aIds.get(0),null),401,"UNAUTHENTICATED");
        assertEquals(401,browser.raw("HEAD","/api/tickets",null,null,null).statusCode());
    }
    @Test void identityAndSortParametersCannotExpandScope() throws Exception {
        var browser=customerA();var suffix="?customerId="+b+"&userId="+b+"&role=STAFF&sort=id,asc";
        assertEquals(reversed(aIds),ids(ok(browser.request("GET","/api/tickets"+suffix,null))));
        error(browser.request("GET","/api/tickets/"+bIds.get(0)+suffix,null),404,"TICKET_NOT_FOUND");
    }
    @Test void unsupportedRoleFailsClosedInsideService() {
        var unknown=new SessionUser.Identity(a,usernameA,"AUTOMATION");
        assertThrows(AccessDeniedException.class,()->service.list(unknown,1,20,null,null));
        assertThrows(AccessDeniedException.class,()->service.detail(unknown,aIds.get(0)));
        // V8 permits ADMIN; automation is not a login business role. No fabricated HTTP session.
    }
    @Test void statusesCombinedWithKeywordAndOwner() throws Exception {
        var browser=customerA();assertEquals(3,ok(browser.request("GET","/api/tickets?status=PENDING",null)).get("total").asInt());
        assertEquals(List.of(aIds.get(2)),ids(ok(browser.request("GET","/api/tickets?status=IN_PROGRESS",null))));
        assertEquals(List.of(aIds.get(3)),ids(ok(browser.request("GET",query("卡纸")+"&status=CLOSED",null))));
        var detail=ok(browser.request("GET","/api/tickets/"+aIds.get(3),null));assertEquals(staff,detail.get("acceptedBy").asLong());assertEquals(2,detail.get("version").asLong());
    }
    @Test void matchesNumberOrderAndNormalizedSymptom() throws Exception {
        var browser=customerA();String number=jdbc.queryForObject("SELECT ticket_no FROM tickets WHERE id=?",String.class,aIds.get(0));
        assertEquals(List.of(aIds.get(0)),ids(ok(browser.request("GET",query(number),null))));
        assertEquals(5,ok(browser.request("GET",query(group+"-A"),null)).get("total").asInt());
        assertEquals(List.of(aIds.get(1)),ids(ok(browser.request("GET",query("　普通卡纸　"),null))));
        assertEquals(0,ok(browser.request("GET",query("模拟联系人"),null)).get("total").asInt());
    }
    @ParameterizedTest @ValueSource(strings={"%","_","!","\\","'","50%_!","🖨"})
    void literalLikeCharacters(String value) throws Exception {
        var browser=customerA();assertEquals(List.of(aIds.get(0)),ids(ok(browser.request("GET",query(value),null))));
    }
    @Test void injectionAndNoMatchAreEmpty() throws Exception {
        var browser=customerA();for(String value:List.of("' OR 1=1 --","%'; SELECT * FROM users; --","不存在-"+UUID.randomUUID())) {
            var page=ok(browser.request("GET",query(value),null));assertEquals(0,page.get("total").asInt());assertEquals(0,page.get("totalPages").asInt());assertTrue(ids(page).isEmpty());
        }
    }
    @Test void pagingStableTotalsAndLargeOffset() throws Exception {
        var browser=customerA();var all=reversed(aIds);var seen=new ArrayList<Long>();
        for(int n=1;n<=3;n++) {var p=ok(browser.request("GET","/api/tickets?page="+n+"&size=2",null));
            assertEquals(5,p.get("total").asInt());assertEquals(3,p.get("totalPages").asInt());seen.addAll(ids(p));}
        assertEquals(all,seen);
        for(String page:List.of("4","2147483647"))assertTrue(ids(ok(browser.request("GET","/api/tickets?page="+page+"&size=2",null))).isEmpty());
        assertEquals(all,ids(ok(browser.request("GET","/api/tickets?size=100",null))));
    }
    @Test void emptyCustomerAndMaximumKeyword() throws Exception {
        var browser=login(emptyUser,passwords.customerA());var p=ok(browser.request("GET","/api/tickets",null));
        assertEquals(0,p.get("total").asInt());assertEquals(0,p.get("totalPages").asInt());assertTrue(ids(p).isEmpty());
        error(browser.request("GET","/api/tickets/"+aIds.get(0),null),404,"TICKET_NOT_FOUND");
        assertEquals(0,ok(customerA().request("GET",query("🖨".repeat(100)),null)).get("total").asInt());
    }
    @ParameterizedTest @ValueSource(strings={"page=0","page=-1","page=2147483648","page=1.5","page=","page=1&page=2",
        "size=0","size=101","size=","size=20&size=20","status=pending","status=","status=UNKNOWN","status=CLOSED&status=PENDING",
        "keyword=","keyword=%20%E3%80%80","keyword=a&keyword=b","page=%20"})
    void invalidQueryIs400AndDoesNotWrite(String query) throws Exception {
        String before=rows();error(customerA().request("GET","/api/tickets?"+query,null),400,"INVALID_ARGUMENT");assertEquals(before,rows());
    }
    @Test void overlongKeywordRejected() throws Exception {
        var before=rows();error(customerA().request("GET",query("🖨".repeat(101)),null),400,"INVALID_ARGUMENT");assertEquals(before,rows());
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","1.0","9223372036854775808"})
    void invalidDetailIdRejected(String id) throws Exception {error(customerA().request("GET","/api/tickets/"+id,null),400,"INVALID_ARGUMENT");}
    @Test void eventsSortedByUtcMicrosecondThenId() throws Exception {
        var d=ok(customerA().request("GET","/api/tickets/"+aIds.get(3),null));var events=d.get("events");assertEquals(4,events.size());
        var expected=jdbc.queryForList("SELECT id FROM ticket_events WHERE ticket_id=? ORDER BY created_at,id",Long.class,aIds.get(3));
        for(int i=0;i<events.size();i++) {var e=events.get(i);assertEquals(expected.get(i),e.get("id").asLong());
            assertEquals(Set.of("id","actorId","eventType","note","createdAt"),e.propertyNames());Instant.parse(e.get("createdAt").asString());}
        assertEquals("2026-10-28T01:02:03.123456Z",events.get(2).get("createdAt").asString());
        assertTrue(events.get(2).get("id").asLong()<events.get(3).get("id").asLong());
    }
    @Test void snapshotIndependentFromMutableDraft() throws Exception {
        String name="snapshot_"+UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,role) VALUES(?,?,'CUSTOMER')",name,encoder.encode(passwords.customerA()));
        long owner=userId(name),model=jdbc.queryForObject("SELECT model_id FROM orders WHERE id=?",Long.class,orderA);
        long ownOrder=order(owner,model,"S-"+UUID.randomUUID());
        var browser=login(name,passwords.customerA());long id=create(browser,ownOrder,"独立快照测试");
        long draft=jdbc.queryForObject("SELECT draft_id FROM tickets WHERE id=?",Long.class,id);
        var before=ok(browser.request("GET","/api/tickets/"+id,null));
        jdbc.update("UPDATE repair_drafts SET symptom='直接SQL隔离夹具改动',contact='草稿新联系人',version=version+1 WHERE id=?",draft);
        assertEquals(before,ok(browser.request("GET","/api/tickets/"+id,null)));
    }
    @Test void readPathsPreserveAllRowsAndDoNotLeakInternalFields() throws Exception {
        String before=rows();var browser=customerA();var staffBrowser=login(usernameStaff,passwords.staff());
        for(var client:List.of(browser,staffBrowser)) {
            var list=ok(client.request("GET",query(group),null));
            var r=client.request("GET","/api/tickets/"+aIds.get(0),null);ok(r);
            for(String key:List.of("customerId","draftId","confirmationId","operationId","payloadHash","password","username"))assertFalse(r.body().contains("\""+key+"\""));
        }
        assertEquals(before,rows());
        assertTrue(mapper.events(bIds.get(0),a).isEmpty());assertNull(mapper.detail(bIds.get(0),a));
        assertEquals(0,mapper.count(a,null,"%他人故障%"));
    }
    @Test void implicitHeadHasSameOwnerBoundary() throws Exception {
        var browser=customerA();assertEquals(200,browser.raw("HEAD","/api/tickets/"+aIds.get(0),null,null,null).statusCode());
        assertEquals(404,browser.raw("HEAD","/api/tickets/"+bIds.get(0),null,null,null).statusCode());
    }
}

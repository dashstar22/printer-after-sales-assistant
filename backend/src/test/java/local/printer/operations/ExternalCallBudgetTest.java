package local.printer.operations;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="PHASE9_DB_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"AI_ENABLED=false","KNOWLEDGE_JOBS_ENABLED=false","KNOWLEDGE_HYBRID_ENABLED=false"})
class ExternalCallBudgetTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("PHASE9_DB_URL"));r.add("spring.datasource.username",()->System.getenv("PHASE9_DB_USER"));r.add("spring.datasource.password",()->System.getenv("PHASE9_DB_PASSWORD"));
    }
    @Autowired ExternalCallBudget budget;@Autowired JdbcTemplate db;@Autowired ConfigurableEnvironment env;@Autowired PlatformTransactionManager tm;
    Map<String,Object> settings;
    @BeforeEach void setup(){
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_p9_"));
        db.update("DELETE FROM external_call_audit");db.update("DELETE FROM external_budget_counters");
        settings=new HashMap<>();settings.put("EXTERNAL_DAILY_CALLS","100");settings.put("EXTERNAL_DAILY_INPUT_BYTES","100000");
        env.getPropertySources().addFirst(new MapPropertySource("phase9-budget-test",settings));
    }
    @Test void allKindsShareDailyReservationAndRetriesAreNotFree(){
        settings.put("EXTERNAL_DAILY_CALLS","4");AtomicInteger calls=new AtomicInteger();
        for(String kind:List.of("CHAT","REWRITE","EMBEDDING","RERANK"))budget.invoke(kind,10,calls::incrementAndGet);
        assertThrows(ExternalCallBudget.Limit.class,()->budget.invoke("EMBEDDING",10,calls::incrementAndGet));assertEquals(4,calls.get());
    }
    @Test void taskRetriesKeepPersistentScopeBound(){
        settings.put("EXTERNAL_TASK_CALLS","2");
        for(int i=0;i<2;i++)try(var scope=ExternalCallBudget.scope("job:retry","TASK",10000)){budget.invoke("EMBEDDING",10,()->null);}
        try(var scope=ExternalCallBudget.scope("job:retry","TASK",10000)){assertThrows(ExternalCallBudget.Limit.class,()->budget.invoke("EMBEDDING",10,()->fail("must not call")));}
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM external_call_audit",Integer.class));
    }
    @Test void concurrentCallsCannotOverspend(){
        settings.put("EXTERNAL_DAILY_CALLS","3");var pool=Executors.newFixedThreadPool(12);AtomicInteger sent=new AtomicInteger();
        try{var futures=new ArrayList<Future<?>>();for(int i=0;i<12;i++)futures.add(pool.submit(()->{try{budget.invoke("CHAT",1,sent::incrementAndGet);}catch(ExternalCallBudget.Limit expected){}}));
            for(var f:futures)assertDoesNotThrow(()->f.get(10,TimeUnit.SECONDS));assertEquals(3,sent.get());
        }finally{pool.shutdownNow();}
    }
    @Test void unknownCostIsNullAndFailureStillConsumes(){
        settings.put("EXTERNAL_DAILY_CALLS","1");assertThrows(IllegalStateException.class,()->budget.invoke("RERANK",40,()->{throw new IllegalStateException("synthetic-secret-body");}));
        var row=db.queryForMap("SELECT * FROM external_call_audit");assertEquals("UNKNOWN",row.get("status"));assertNull(row.get("cost_amount"));assertNull(row.get("input_tokens"));
        assertThrows(ExternalCallBudget.Limit.class,()->budget.invoke("RERANK",40,()->null));
    }
    @Test void reservationSurvivesBusinessRollback(){
        new TransactionTemplate(tm).executeWithoutResult(s->{budget.invoke("CHAT",10,()->null);s.setRollbackOnly();});
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM external_call_audit",Integer.class));
    }
    @Test void inputAndPerKindLimitsRejectBeforeIO(){
        settings.put("EXTERNAL_EMBEDDING_DAILY_INPUT_BYTES","5");
        assertThrows(ExternalCallBudget.Limit.class,()->budget.invoke("EMBEDDING",6,()->fail("must not call")));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM external_call_audit",Integer.class));
        settings.put("EXTERNAL_CHAT_DAILY_CALLS","0");assertThrows(ExternalCallBudget.Limit.class,()->budget.invoke("CHAT",1,()->null));
    }
    @Test void turnBudgetCoversDifferentKinds(){
        settings.put("EXTERNAL_TURN_CALLS","1");try(var scope=ExternalCallBudget.scope("turn:shared","TURN",1000)){
            budget.invoke("REWRITE",3,()->null);assertThrows(ExternalCallBudget.Limit.class,()->budget.invoke("EMBEDDING",1,()->null));
        }
    }
    @Test void usageIsObservedWithoutInventingMoney(){
        var response=JsonMapper.builder().build().readTree("{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}");budget.invoke("CHAT",40,()->response);
        var row=db.queryForMap("SELECT * FROM external_call_audit");assertEquals(12L,((Number)row.get("input_tokens")).longValue());assertEquals(3L,((Number)row.get("output_tokens")).longValue());assertNull(row.get("cost_amount"));
    }
    @Test void missingUsageRemainsUnknownAndScopeExpiryBlocks(){
        budget.invoke("EMBEDDING",1,()->JsonMapper.builder().build().readTree("{}"));assertNull(db.queryForMap("SELECT * FROM external_call_audit").get("input_tokens"));
        try(var scope=ExternalCallBudget.scope("turn:expired","TURN",1)){Thread.sleep(5);assertThrows(ExternalCallBudget.Limit.class,()->budget.invoke("CHAT",1,()->fail("must not call")));}catch(InterruptedException e){throw new RuntimeException(e);}
    }
    @Test void failedHttpResponseNeverClaimsSupplierSuccess(){
        @SuppressWarnings("unchecked") var response=(java.net.http.HttpResponse<byte[]>)org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(503);
        org.mockito.Mockito.when(response.body()).thenReturn("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        budget.invoke("EMBEDDING",4,()->response);
        var row=db.queryForMap("SELECT * FROM external_call_audit");assertEquals("UNKNOWN",row.get("status"));assertNull(row.get("cost_amount"));
    }
}

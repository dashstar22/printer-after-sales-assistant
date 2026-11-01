package local.printer.assistant;

import dev.langchain4j.exception.HttpException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class AssistantExecutionTest {
    final MockEnvironment env=new MockEnvironment();
    final AssistantExecution execution=new AssistantExecution(env);
    @AfterEach void close(){execution.close();}
    @ParameterizedTest @ValueSource(strings={"ASSISTANT_MAX_MODEL_CALLS","ASSISTANT_MAX_TOOL_CALLS","ASSISTANT_REQUEST_TIMEOUT_MS","ASSISTANT_TURN_TIMEOUT_MS"})
    void invalidNonpositiveBudgetRejected(String name){env.withProperty(name,"0");assertThrows(IllegalArgumentException.class,execution::begin);}
    @Test void retriesCannotBeUnlimited(){env.withProperty("ASSISTANT_READ_RETRIES","2");assertThrows(IllegalArgumentException.class,execution::begin);}
    @Test void modelAttemptsIncludingRetryRespectCap(){
        env.withProperty("ASSISTANT_MAX_MODEL_CALLS","1");var budget=execution.begin();var attempts=new AtomicInteger();
        var failure=assertThrows(AssistantExecution.Stopped.class,()->budget.model(()->{attempts.incrementAndGet();throw new HttpException(503,"redacted");}));
        assertEquals("MODEL_CALL_LIMIT",failure.code());assertEquals(1,attempts.get());assertEquals(1,budget.modelCalls());
    }
    @Test void oneTransientReadRetrySucceedsButAuthenticationIsNotRetried(){
        var budget=execution.begin();var attempts=new AtomicInteger();
        assertEquals("ok",budget.model(()->{if(attempts.incrementAndGet()==1)throw new HttpException(503,"redacted");return "ok";}));
        assertEquals(2,budget.modelCalls());assertEquals(2,attempts.get());
        var rejected=execution.begin();attempts.set(0);
        assertThrows(HttpException.class,()->rejected.model(()->{attempts.incrementAndGet();throw new HttpException(401,"redacted");}));
        assertEquals(1,attempts.get());
    }
    @Test void readRetriesCanBeDisabled(){
        env.withProperty("ASSISTANT_READ_RETRIES","0");var budget=execution.begin();var attempts=new AtomicInteger();
        assertThrows(HttpException.class,()->budget.model(()->{attempts.incrementAndGet();throw new HttpException(503,"redacted");}));assertEquals(1,attempts.get());
    }
    @Test void toolLimitIsCheckedBeforeAnotherInvocation(){
        env.withProperty("ASSISTANT_MAX_TOOL_CALLS","2");var budget=execution.begin();budget.tool();budget.tool();
        assertEquals("TOOL_CALL_LIMIT",assertThrows(AssistantExecution.Stopped.class,budget::tool).code());assertEquals(2,budget.toolCalls());
    }
    @Test void dependencyTimeoutStopsFurtherActionsAndCleansThreadScope() throws Exception {
        env.withProperty("ASSISTANT_REQUEST_TIMEOUT_MS","80");var budget=execution.begin();var interrupted=new CountDownLatch(1);
        long start=System.nanoTime();
        assertEquals("TOOL_TIMEOUT",assertThrows(AssistantExecution.Stopped.class,()->budget.call("TOOL",()->{
            try {new CountDownLatch(1).await();return null;}finally{interrupted.countDown();}
        })).code());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);assertTrue(interrupted.await(1,TimeUnit.SECONDS));
        assertThrows(AssistantExecution.Stopped.class,budget::tool);assertNull(AssistantExecution.current());
        assertEquals("ok",execution.begin().call("TOOL",()->"ok"));
    }
    @Test void totalDeadlineIsSharedAcrossCalls(){
        env.withProperty("ASSISTANT_REQUEST_TIMEOUT_MS","500").withProperty("ASSISTANT_TURN_TIMEOUT_MS","140");var budget=execution.begin();
        budget.call("TOOL",()->{Thread.sleep(75);return null;});
        assertEquals("TURN_TIMEOUT",assertThrows(AssistantExecution.Stopped.class,()->budget.model(()->{Thread.sleep(120);return "late";})).code());
        assertEquals(1,budget.modelCalls());
    }
    @Test void writesAreNeverAutomaticallyRetried(){
        var budget=execution.begin();var writes=new AtomicInteger();
        assertThrows(HttpException.class,()->budget.call("WRITE",()->{writes.incrementAndGet();throw new HttpException(503,"redacted");}));assertEquals(1,writes.get());
    }
    @Test void writeTimeoutMeansUnknownAndLateWorkerCannotStartAnotherWrite() throws Exception {
        env.withProperty("ASSISTANT_REQUEST_TIMEOUT_MS","80");var budget=execution.begin();var writes=new AtomicInteger();var done=new CountDownLatch(1);
        assertEquals("WRITE_RESULT_UNKNOWN",assertThrows(AssistantExecution.Stopped.class,()->budget.call("WRITE",()->{
            try {Thread.sleep(500);budget.check();writes.incrementAndGet();return null;}finally{done.countDown();}
        })).code());
        assertTrue(done.await(1,TimeUnit.SECONDS));assertEquals(0,writes.get());assertThrows(AssistantExecution.Stopped.class,()->budget.call("WRITE",()->{writes.incrementAndGet();return null;}));
    }
}

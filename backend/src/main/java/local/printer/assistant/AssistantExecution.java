package local.printer.assistant;

import dev.langchain4j.exception.HttpException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** T029: bounded waiting, attempt budgets and cooperative cancellation; never retries writes. */
@Component
public final class AssistantExecution {
    @org.springframework.beans.factory.annotation.Autowired
    private local.printer.operations.ExternalCallBudget externalBudget;
    private final Environment env;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{
        var t=new Thread(r,"assistant-dependency");t.setDaemon(true);return t;
    },new ThreadPoolExecutor.AbortPolicy());
    public AssistantExecution(Environment env){this.env=env;}
    @PreDestroy public void close(){workers.shutdownNow();}
    public record Limits(int modelCalls,int toolCalls,long requestMs,long turnMs,int readRetries){
        public Limits {
            if(modelCalls<1||modelCalls>16||toolCalls<1||toolCalls>32||requestMs<10||requestMs>120000
                ||turnMs<10||turnMs>180000||readRetries<0||readRetries>1)throw new IllegalArgumentException("ASSISTANT_CONFIG_INVALID");
        }
    }
    public Budget begin(){return new Budget(new Limits(number("ASSISTANT_MAX_MODEL_CALLS",4),number("ASSISTANT_MAX_TOOL_CALLS",6),
        number("ASSISTANT_REQUEST_TIMEOUT_MS",20000),number("ASSISTANT_TURN_TIMEOUT_MS",60000),number("ASSISTANT_READ_RETRIES",1)));}
    private int number(String key,int fallback){return env.getProperty(key,Integer.class,fallback);}
    public static final class Stopped extends RuntimeException {
        private final String code;
        Stopped(String code){super(code);this.code=code;}
        public String code(){return code;}
    }
    private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
    /** Available only inside a dependency worker; maintenance/publication never installs this scope. */
    public static Scope current(){return ACTIVE.get();}
    public final class Scope {
        private final Budget budget;private final long deadline;private final String kind;
        Scope(Budget budget,long deadline,String kind){this.budget=budget;this.deadline=deadline;this.kind=kind;}
        public Duration remaining(){
            budget.check();long left=deadline-System.nanoTime();
            if(left<=0)throw budget.stop(kind.equals("WRITE")?"WRITE_RESULT_UNKNOWN":kind+"_TIMEOUT");
            return Duration.ofNanos(left);
        }
        public int readRetries(){return budget.limits.readRetries();}
    }
    public final class Budget {
        private final String scopeId="turn:"+java.util.UUID.randomUUID();
        private final Limits limits;private final long deadline;
        private final AtomicReference<Stopped> stopped=new AtomicReference<>();
        private int modelCalls,toolCalls;
        Budget(Limits limits){this.limits=limits;this.deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(limits.turnMs());}
        public int modelCalls(){return modelCalls;}
        public int toolCalls(){return toolCalls;}
        public String stoppedCode(){return stopped.get()==null?null:stopped.get().code();}
        private Stopped stop(String code){stopped.compareAndSet(null,new Stopped(code));return stopped.get();}
        public void check(){
            if(stopped.get()!=null)throw stopped.get();
            if(deadline-System.nanoTime()<=0)throw stop("TURN_TIMEOUT");
            if(Thread.currentThread().isInterrupted())throw stop("ASSISTANT_CANCELLED");
        }
        public Duration requestTimeout(){check();return Duration.ofNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(limits.requestMs()),deadline-System.nanoTime()));}
        public void tool(){check();if(toolCalls>=limits.toolCalls())throw stop("TOOL_CALL_LIMIT");toolCalls++;}
        public <T>T model(Callable<T> work){
            for(int attempt=0;;attempt++){
                check();if(modelCalls>=limits.modelCalls())throw stop("MODEL_CALL_LIMIT");modelCalls++;
                long started=AssistantAudit.clock();
                try{var response=call("MODEL",work);AssistantAudit.model(response,null,started);return response;}
                catch(RuntimeException failure){
                    AssistantAudit.model(null,failure,started);
                    if(stopped.get()!=null)throw stopped.get();
                    for(Throwable cause=failure;cause!=null;cause=cause.getCause())
                        if(cause instanceof dev.langchain4j.exception.TimeoutException||cause instanceof java.net.http.HttpTimeoutException)
                            throw stop(deadline-System.nanoTime()<=0?"TURN_TIMEOUT":"MODEL_TIMEOUT");
                    if(attempt>=limits.readRetries()||!transientRead(failure))throw failure;
                }
            }
        }
        public <T>T call(String kind,Callable<T> work){
            check();long wait=Math.min(TimeUnit.MILLISECONDS.toNanos(limits.requestMs()),deadline-System.nanoTime());
            var scope=new Scope(this,System.nanoTime()+wait,kind);Future<T> future;
            try {future=workers.submit(()->{ACTIVE.set(scope);try(var externalScope=local.printer.operations.ExternalCallBudget.scope(scopeId,"TURN",Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())))){scope.remaining();return work.call();}finally{ACTIVE.remove();}});}
            catch(RejectedExecutionException busy){throw stop("ASSISTANT_BUSY");}
            try {var result=future.get(wait,TimeUnit.NANOSECONDS);scope.remaining();return result;}
            catch(java.util.concurrent.TimeoutException timeout){
                var reason=stop(kind.equals("WRITE")?"WRITE_RESULT_UNKNOWN":deadline-System.nanoTime()<=0?"TURN_TIMEOUT":kind+"_TIMEOUT");
                future.cancel(true);workers.remove((Runnable)future);throw reason;
            } catch(InterruptedException interrupted){var reason=stop("ASSISTANT_CANCELLED");future.cancel(true);Thread.currentThread().interrupt();throw reason;}
            catch(ExecutionException failure){
                if(stopped.get()!=null)throw stopped.get();
                if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
                throw new IllegalStateException("DEPENDENCY_UNAVAILABLE");
            }
        }
        public <T>T external(String kind,long bytes,Callable<T> call){return externalBudget==null?unchecked(call):externalBudget.invoke(kind,bytes,call);}
        private <T>T unchecked(Callable<T> call){try{return call.call();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("MODEL_UNAVAILABLE");}}
    }
    static boolean transientRead(Throwable failure){
        for(var cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof HttpException http)return http.statusCode()==429||http.statusCode()>=500;
            if(cause instanceof dev.langchain4j.exception.NonRetriableException)return false;
            if(cause instanceof IOException || cause instanceof dev.langchain4j.exception.RetriableException)return true;
        }
        return false;
    }
}

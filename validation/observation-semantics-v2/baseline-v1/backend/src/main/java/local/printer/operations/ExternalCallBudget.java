package local.printer.operations;

import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Reserve before I/O in independent transactions; never refund an ambiguous attempt. */
@Service
public class ExternalCallBudget {
    private final JdbcTemplate db; private final Environment env; private final TransactionTemplate tx;
    private final Semaphore slots;
    private record Context(String id,String type,long deadline){}
    private static final ThreadLocal<Context> ACTIVE=new ThreadLocal<>();
    public static final class Limit extends RuntimeException { public Limit(){super("EXTERNAL_BUDGET_EXHAUSTED");} }
    public static final class Scope implements AutoCloseable {
        private final Context previous;
        private Scope(Context next){previous=ACTIVE.get();ACTIVE.set(next);}
        public void close(){if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
    }
    public static Scope scope(String id,String type,long millis){
        if(!Set.of("TURN","TASK").contains(type)||!id.matches("[A-Za-z0-9:_-]{1,80}")||millis<1)throw new IllegalArgumentException("BUDGET_SCOPE_INVALID");
        return new Scope(new Context(id,type,System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis)));
    }
    public ExternalCallBudget(JdbcTemplate db,Environment env,PlatformTransactionManager tm){
        this.db=db;this.env=env;tx=new TransactionTemplate(tm);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
        slots=new Semaphore((int)config("EXTERNAL_MAX_CONCURRENT",4));
    }
    private long config(String name,long fallback){long n=env.getProperty(name,Long.class,fallback);if(n<0||n>1_000_000_000L)throw new IllegalArgumentException("BUDGET_CONFIG_INVALID");return n;}
    public static long bytes(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
    public static long remainingMillis(long configured){var c=ACTIVE.get();if(c==null)return configured;long left=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(c.deadline()-System.nanoTime());if(left<=0)throw new Limit();return Math.min(configured,left);}
    public <T>T invoke(String kind,long inputBytes,Callable<T> call){
        if(!Set.of("CHAT","REWRITE","EMBEDDING","RERANK").contains(kind)||inputBytes<0)throw new IllegalArgumentException("BUDGET_INPUT_INVALID");
        Context c=ACTIVE.get();if(c==null)c=new Context("single:"+UUID.randomUUID(),"TASK",System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120));
        if(System.nanoTime()>=c.deadline()||!slots.tryAcquire())throw new Limit();
        String id=UUID.randomUUID().toString();long start=System.nanoTime();boolean reserved=false;
        try{
            Context context=c;
            tx.executeWithoutResult(s->{
                String day=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
                reserve("day:"+day,config("EXTERNAL_DAILY_CALLS",500),config("EXTERNAL_DAILY_INPUT_BYTES",2_000_000),inputBytes);
                reserve("day:"+day+":"+kind,config("EXTERNAL_"+kind+"_DAILY_CALLS",300),config("EXTERNAL_"+kind+"_DAILY_INPUT_BYTES",1_000_000),inputBytes);
                reserve("scope:"+context.id(),config("EXTERNAL_"+context.type()+"_CALLS",context.type().equals("TURN")?12:100),config("EXTERNAL_"+context.type()+"_INPUT_BYTES",context.type().equals("TURN")?200_000:1_000_000),inputBytes);
                db.update("INSERT INTO external_call_audit(id,scope_id,kind,status,reserved_input_bytes) VALUES(?,?,?,'RESERVED',?)",id,context.id(),kind,inputBytes);
            });
            reserved=true;
            T response=call.call();
            String status=response instanceof java.net.http.HttpResponse<?> http && (http.statusCode()<200||http.statusCode()>=300)?"UNKNOWN":"SUCCEEDED";
            finish(id,status,response,start,kind,c.id());return response;
        }catch(RuntimeException e){if(reserved)finish(id,"UNKNOWN",null,start,kind,c.id());throw e;}
        catch(Exception e){if(reserved)finish(id,"UNKNOWN",null,start,kind,c.id());throw new IllegalStateException("EXTERNAL_CALL_UNAVAILABLE");}
        finally{slots.release();}
    }
    private void reserve(String key,long maxCalls,long maxBytes,long bytes){
        // Upsert takes the exclusive duplicate-row lock immediately. INSERT IGNORE followed
        // by FOR UPDATE can deadlock when concurrent readers upgrade shared duplicate locks.
        db.update("INSERT INTO external_budget_counters(budget_key) VALUES(?) ON DUPLICATE KEY UPDATE attempts=attempts",key);
        var row=db.queryForMap("SELECT attempts,input_bytes FROM external_budget_counters WHERE budget_key=? FOR UPDATE",key);
        if(((Number)row.get("attempts")).longValue()>=maxCalls||((Number)row.get("input_bytes")).longValue()+bytes>maxBytes)throw new Limit();
        db.update("UPDATE external_budget_counters SET attempts=attempts+1,input_bytes=input_bytes+? WHERE budget_key=?",bytes,key);
    }
    private void finish(String id,String status,Object response,long start,String kind,String scope){
        Long in=null,out=null;
        if(response instanceof ChatResponse chat&&chat.tokenUsage()!=null){
            if(chat.tokenUsage().inputTokenCount()!=null)in=chat.tokenUsage().inputTokenCount().longValue();
            if(chat.tokenUsage().outputTokenCount()!=null)out=chat.tokenUsage().outputTokenCount().longValue();
        }else {
            if(response instanceof java.net.http.HttpResponse<?> http&&http.body() instanceof byte[] bytes){
                try{response=tools.jackson.databind.json.JsonMapper.builder().build().readTree(bytes);}catch(RuntimeException invalid){response=null;}
            }
            if(response instanceof JsonNode json){
            var usage=json.path("usage");in=number(usage,"input_tokens","prompt_tokens");out=number(usage,"output_tokens","completion_tokens");
            }
        }
        Long input=in,output=out;long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        tx.executeWithoutResult(s->db.update("UPDATE external_call_audit SET status=?,input_tokens=?,output_tokens=?,duration_ms=? WHERE id=? AND status='RESERVED'",status,input,output,elapsed,id));
        LoggerFactory.getLogger(getClass()).info("EXTERNAL_CALL id={} scope={} kind={} status={} durationMs={} cost=UNKNOWN",id,scope,kind,status,elapsed);
    }
    private static Long number(JsonNode j,String a,String b){var n=j.has(a)?j.path(a):j.path(b);return n.isIntegralNumber()&&n.canConvertToLong()&&n.asLong()>=0?n.asLong():null;}
    public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("timezone","Asia/Shanghai");result.put("inputUnit","UTF8_BYTES_RESERVED");result.put("costStatus","UNKNOWN_NO_PRICING_OR_INVOICE");
        result.put("reservationMeaning","RESERVED after interruption means outcome/usage unknown; never refund automatically");
        result.put("dailyCalls",config("EXTERNAL_DAILY_CALLS",500));result.put("dailyInputBytes",config("EXTERNAL_DAILY_INPUT_BYTES",2_000_000));
        result.put("counters",db.queryForList("SELECT budget_key,attempts,input_bytes FROM external_budget_counters WHERE budget_key LIKE ? ORDER BY budget_key","day:"+LocalDate.now(ZoneId.of("Asia/Shanghai"))+"%"));
        result.put("recent",db.queryForList("SELECT id,scope_id,kind,status,reserved_input_bytes,input_tokens,output_tokens,cost_amount,started_at,duration_ms FROM external_call_audit ORDER BY started_at DESC LIMIT 30"));return result;
    }
}

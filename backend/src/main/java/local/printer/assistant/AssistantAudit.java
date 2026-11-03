package local.printer.assistant;

import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** T030：只记录白名单元数据；请求正文、模型文本和工具参数均不进入日志。 */
public final class AssistantAudit {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final ThreadLocal<Run> ACTIVE=new ThreadLocal<>();
    private static final Set<String> TOOLS=Set.of("getMyOrder","searchKnowledge","prepareRepairDraft","createServiceTicket");
    private static final Set<String> CODES=Set.of("SUCCEEDED","FAILED","ANSWERED","NEEDS_INPUT","DRAFT_PREPARED","DRAFT_CONFLICT",
        "TOOL_REJECTED","PAGE_CONFIRMATION_REQUIRED","ORDER_FOUND","HIT","NO_EVIDENCE","RETRIEVAL_UNAVAILABLE",
        "EVIDENCE_NOT_AUTHORIZED","REFERENCE_REJECTED","QUERY_REQUIRES_USER_TEXT","MODEL_UNAVAILABLE","MODEL_RESPONSE_REJECTED","DEGRADED","EXCERPT_ONLY","MODEL_CLARIFICATION_REQUIRED",
        "MODEL_CALL_LIMIT","TOOL_CALL_LIMIT","MODEL_TIMEOUT","TOOL_TIMEOUT","TURN_TIMEOUT","WRITE_RESULT_UNKNOWN",
        "ASSISTANT_BUSY","ASSISTANT_CANCELLED","ASSISTANT_NOT_CONNECTED","RESULT_UNKNOWN","TICKET_CREATED",
        "AI_DISABLED","NOT_REQUESTED_ON_REPLAY","CHECKPOINT_UNAVAILABLE","DATABASE_UNAVAILABLE","CONVERSATION_NOT_FOUND",
        "CONVERSATION_CONFLICT","INVALID_ARGUMENT","UNSUPPORTED_MEDIA_TYPE","UNAUTHENTICATED","FORBIDDEN","CSRF_INVALID",
        "ORDER_NOT_FOUND","OPERATION_NOT_FOUND","CONFIRMATION_CONFLICT","CONFIRMATION_EXPIRED","OPERATION_CONFLICT",
        "HTTP_SUCCEEDED","HTTP_REJECTED","HTTP_FAILED","UNCLASSIFIED");
    private AssistantAudit() {}
    static Run open(String traceId,String operation,String modelMode){
        var run=new Run(traceId,operation,modelMode);ACTIVE.set(run);return run;
    }
    static void clear(){ACTIVE.remove();}
    public static Run current(){return ACTIVE.get();}
    public static long clock(){return System.nanoTime();}
    public static long elapsed(long start){return Math.max(0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}
    public static String code(String value){return CODES.contains(value==null?"":value)?value:"UNCLASSIFIED";}
    public static String toolName(String value){return TOOLS.contains(value==null?"":value)?value:"UNREGISTERED";}
    public static void result(String status,boolean replayed){var run=current();if(run!=null){run.result=code(status);run.replayed=replayed;}}
    public static void tool(String name,String status,long started,String mode){var run=current();if(run!=null)run.tool(name,status,started,mode);}
    public static void model(Object response,RuntimeException failure,long started){var run=current();if(run!=null)run.model(response,failure,started);}
    /** 给既有T026/T027记录加关联信息，不改变旧回执字段。 */
    public static void decorate(Map<String,Object> event){var run=current();if(run!=null){event.put("traceId",run.traceId);event.put("modelMode",run.modelMode);event.put("durationMs",elapsed(run.started));}}

    public static final class Run {
        private final String traceId,operation,modelMode;
        private final long started=clock();
        private int sequence,modelAttempts,toolCalls,knownUsage,completeUsage;
        private Long inputTokens,outputTokens,totalTokens;
        private boolean replayed;
        private String result;
        Run(String traceId,String operation,String modelMode){this.traceId=traceId;this.operation=operation;this.modelMode=modelMode;}
        private Map<String,Object> event(String type,String mode,String status,long duration){
            var row=new LinkedHashMap<String,Object>();row.put("traceId",traceId);row.put("sequence",++sequence);
            row.put("event",type);row.put("operation",operation);row.put("mode",mode);row.put("modelMode",modelMode);
            row.put("status",code(status));row.put("durationMs",duration);return row;
        }
        private void emit(Map<String,Object> row){LoggerFactory.getLogger(AssistantAudit.class).info("ASSISTANT_AUDIT {}",JSON.writeValueAsString(row));}
        void model(Object response,RuntimeException failure,long start){
            modelAttempts++;String status=failureCode(failure);
            var row=event("MODEL_ATTEMPT","MODEL_API",status,elapsed(start));row.put("attempt",modelAttempts);
            var usage=response instanceof ChatResponse chat?chat.tokenUsage():null;
            Integer in=usage==null?null:nonnegative(usage.inputTokenCount()),out=usage==null?null:nonnegative(usage.outputTokenCount()),total=usage==null?null:nonnegative(usage.totalTokenCount());
            String availability=in==null&&out==null&&total==null?"UNAVAILABLE":in!=null&&out!=null&&total!=null?"COMPLETE":"PARTIAL";
            if(!availability.equals("UNAVAILABLE"))knownUsage++;
            if(availability.equals("COMPLETE"))completeUsage++;
            row.put("usageStatus",availability);row.put("inputTokens",in);row.put("outputTokens",out);row.put("totalTokens",total);
            row.put("usageSource","MODEL_RESPONSE_METADATA");row.put("cost",null);
            inputTokens=add(inputTokens,in);outputTokens=add(outputTokens,out);totalTokens=add(totalTokens,total);emit(row);
        }
        void tool(String name,String status,long start,String mode){
            toolCalls++;var row=event("TOOL",mode,status,elapsed(start));row.put("tool",toolName(name));row.put("toolCall",toolCalls);emit(row);
        }
        void finish(int httpStatus,String errorCode,boolean failed){
            String status=failed?"HTTP_FAILED":httpStatus>=400?code(errorCode==null?"HTTP_REJECTED":errorCode):result==null?"HTTP_SUCCEEDED":result;
            String mode=operation.contains("TICKET_EXECUTE")?"SERVER_COORDINATOR":operation.equals("MESSAGE")?modelMode.equals("DISABLED")?"PERSISTENCE_ONLY":"MODEL_API":"BUSINESS_API";
            var row=event("REQUEST",mode,status,elapsed(started));row.put("httpStatus",failed?500:httpStatus);row.put("replayed",replayed);
            row.put("modelAttempts",modelAttempts);row.put("toolCalls",toolCalls);
            row.put("usageStatus",modelAttempts==0?"NOT_REQUESTED":knownUsage==0?"UNAVAILABLE":completeUsage==modelAttempts?"COMPLETE":"PARTIAL");
            row.put("observedInputTokens",inputTokens);row.put("observedOutputTokens",outputTokens);row.put("observedTotalTokens",totalTokens);row.put("cost",null);emit(row);
        }
        private static Integer nonnegative(Integer value){return value==null||value<0?null:value;}
        private static Long add(Long sum,Integer value){
            if(value==null)return sum;
            return sum==null?Long.valueOf(value.longValue()):Long.valueOf(sum+value);
        }
        private static String failureCode(RuntimeException failure){
            if(failure==null)return "SUCCEEDED";
            if(failure instanceof AssistantExecution.Stopped stopped)return stopped.code();
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())
                if(cause instanceof dev.langchain4j.exception.TimeoutException||cause instanceof java.net.http.HttpTimeoutException)return "MODEL_TIMEOUT";
            return "MODEL_UNAVAILABLE";
        }
    }
}

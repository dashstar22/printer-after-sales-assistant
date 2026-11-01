package local.printer.assistant;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Optional local checkpoints. No prompt, field value, model prose, arguments, identifiers or exception text. */
final class EditDiagnosticEvidence {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> FIELDS=Set.of("symptom","extra","contact","contactMethod");
    private static final Set<String> TOOLS=Set.of("getMyOrder","searchKnowledge","prepareRepairDraft","createServiceTicket");
    private final String directory,turn;
    private int sequence;
    private boolean incomplete;
    EditDiagnosticEvidence(String directory,String turn){this.directory=directory;this.turn=turn;}
    static String hash(String text){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException();}
    }
    private static String toolName(String name){return TOOLS.contains(name==null?"":name)?name:"UNREGISTERED";}
    String write(String phase,int attempt,Map<String,Object> fields){
        if(directory==null || directory.isBlank())return "DISABLED";
        try{
            var row=new LinkedHashMap<String,Object>();row.put("schema","T031_EDIT_DIAGNOSTIC_V1");
            row.put("turnId",UUID.fromString(turn).toString());row.put("phase",phase);row.put("modelAttempt",attempt);
            row.put("sequence",++sequence);row.put("priorCheckpointMissing",incomplete);row.putAll(fields);
            Path root=Path.of(directory).toAbsolutePath().normalize().resolve("edit");Files.createDirectories(root);
            Files.writeString(root.resolve(turn+"-"+sequence+".json"),JSON.writeValueAsString(row),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            return "WRITTEN";
        }catch(Exception unavailable){
            incomplete=true;
            LoggerFactory.getLogger(EditDiagnosticEvidence.class).warn("T031_EDIT_DIAGNOSTIC_UNVERIFIED phase={} sequence={}",phase,sequence);
            return "UNVERIFIED";
        }
    }
    void request(int attempt,ChatRequest request){
        var systems=request.messages().stream().filter(m->m instanceof SystemMessage).map(m->((SystemMessage)m).text()).toList();
        write("REQUEST_PREPARED",attempt,Map.of("toolChoice",request.toolChoice()==null?"AUTO":request.toolChoice().name(),
            "tools",request.toolSpecifications().stream().map(t->toolName(t.name())).toList(),
            "systemSha256",hash(String.join("\n",systems)),"messageCount",request.messages().size()));
    }
    void response(int attempt,AiMessage answer){
        var row=new LinkedHashMap<String,Object>();row.put("toolCallsPresent",answer!=null && answer.hasToolExecutionRequests());
        row.put("toolNames",answer==null || !answer.hasToolExecutionRequests()?List.of():answer.toolExecutionRequests().stream().map(t->toolName(t.name())).toList());
        row.put("argumentSha256",answer==null || !answer.hasToolExecutionRequests()?List.of():answer.toolExecutionRequests().stream().map(t->hash(t.arguments()==null?"":t.arguments())).toList());
        String text=answer==null?null:answer.text();row.put("textSha256",hash(text==null?"":text));
        var classification=ObservationBranchEvidence.classify(text);row.put("outputShape",classification.get(0));row.put("kind",classification.get(1));
        row.put("intent","OTHER");row.put("missingFields",List.of());
        try{
            var value=JSON.readTree(text);String intent=value.path("intent").asString();
            row.put("intent",Set.of("ASK_MISSING","VIEW_TICKETS").contains(intent)?intent:"OTHER");
            var fields=new ArrayList<String>();var requested=value.path("missingFields");
            if(requested.isArray())for(var field:requested)if(field.isString() && FIELDS.contains(field.asString()) && !fields.contains(field.asString()))fields.add(field.asString());
            row.put("missingFields",fields);
        }catch(RuntimeException ignored){}
        write("MODEL_PARSED",attempt,row);
    }
    void tool(int attempt,String name,String status){write("TOOL_RESULT",attempt,Map.of("tool",toolName(name),"status",AssistantAudit.code(status)));}
    void outcome(String route,String status,boolean persisted,Boolean draftSaved,int models,int tools){
        var row=new LinkedHashMap<String,Object>();
        row.put("route",route);row.put("status",AssistantAudit.code(status));row.put("replyPersisted",persisted);
        row.put("draftSaved",draftSaved);
        row.put("draftPersistence",draftSaved==null?"UNKNOWN":draftSaved?"SAVED":"NOT_SAVED");
        row.put("modelCalls",models);row.put("toolCalls",tools);
        write("HOST_OUTCOME",models,row);
    }
}

package local.printer.assistant;

import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly enabled local proof only. Never writes model text, arguments or exceptions. */
final class ObservationBranchEvidence {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> REASONS=Set.of("VALID","INITIAL_MISSING","TARGET_INELIGIBLE","GRAMMAR_MISMATCH","HISTORY_PAIR_INVALID",
        "HISTORY_STATE_INVALID","HISTORY_TEMPLATE_INVALID","TIME_LIMIT","ROUND_LIMIT");
    private static final Set<String> SHAPES=Set.of("NOT_OBSERVED","OBJECT","NON_OBJECT","MALFORMED");
    private static final Set<String> KINDS=Set.of("WORKFLOW","KNOWLEDGE","NO_EVIDENCE");
    private static final Set<String> DECISIONS=Set.of("ORIGINAL","FIXED_PROMPT","REJECTED_WITH_PROMPT");
    private ObservationBranchEvidence() {}
    private static String allowed(String value,Set<String> values){return values.contains(value==null?"":value)?value:"OTHER";}
    static List<String> classify(String text){
        try {
            var node=JSON.readTree(text);
            return List.of(node!=null && node.isObject()?"OBJECT":"NON_OBJECT",
                node!=null && node.isObject()?allowed(node.path("kind").asString(),KINDS):"OTHER");
        } catch(RuntimeException invalid){return List.of("MALFORMED","OTHER");}
    }
    static String write(String directory,String turn,String reason,String shape,String kind,String decision,
                        boolean tools,boolean retrieval,boolean writes,String finalStatus){
        if(directory==null || directory.isBlank())return "DISABLED";
        try {
            String id=UUID.fromString(turn).toString();
            Path root=Path.of(directory).toAbsolutePath().normalize();Files.createDirectories(root);
            var row=new LinkedHashMap<String,Object>();row.put("schema","T031_OBSERVATION_BRANCH_V1");row.put("turnId",id);
            row.put("stateReason",allowed(reason,REASONS));row.put("outputShape",allowed(shape,SHAPES));
            row.put("outputKind",allowed(kind,KINDS));row.put("decision",allowed(decision,DECISIONS));
            row.put("toolActivity",tools);row.put("retrievalActivity",retrieval);row.put("writeActivity",writes);
            row.put("finalStatus",AssistantAudit.code(finalStatus));
            Files.writeString(root.resolve(id+".json"),JSON.writeValueAsString(row),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            return "WRITTEN";
        } catch(Exception unavailable){return "UNVERIFIED";}
    }
}

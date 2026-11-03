package local.printer.knowledge;

import java.util.*;
import local.printer.knowledge.KnowledgeRetrievalService.Source;
import local.printer.knowledge.index.KnowledgeIndexPipeline;
import tools.jackson.databind.json.JsonMapper;

/** The counted representation is the representation actually sent to the existing chat tool. */
public final class KnowledgeEvidenceContext {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private KnowledgeEvidenceContext(){}
    public static Map<String,Object> item(Source s){return Map.of("pointId",s.pointId(),"content",s.content(),"title",s.title(),"version",s.version(),"simulated",s.simulated(),
        "useCondition",Objects.toString(s.useCondition(),""),"models",s.models(),"modelScope",s.modelScope(),"pageNumber",s.pageNumber()==null?"":s.pageNumber(),"section",Objects.toString(s.section(),""));}
    public static String serialized(Source s){return JSON.writeValueAsString(item(s));}
    /** Identical wording does not make different source versions or applicability conditions interchangeable. */
    public static String deduplicationScope(Source s){return KnowledgeIndexPipeline.digest(s.documentId(),s.version(),s.title(),s.modelScope(),s.models(),s.useCondition());}
}

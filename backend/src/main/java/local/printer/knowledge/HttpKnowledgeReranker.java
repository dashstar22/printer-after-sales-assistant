package local.printer.knowledge;

import java.net.URI;
import java.util.*;
import local.printer.knowledge.index.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Thin DashScope native rerank adapter. No automatic supplier retries. */
@Component
@ConditionalOnProperty(name="app.knowledge.hybrid.enabled",havingValue="true")
public class HttpKnowledgeReranker {
    @org.springframework.beans.factory.annotation.Autowired
    private local.printer.operations.ExternalCallBudget budget;
    private final Environment env;private final IndexHttp http=new IndexHttp();
    public HttpKnowledgeReranker(Environment env){this.env=env;}
    public String configurationId(){return env.getProperty("KNOWLEDGE_RERANK_CONFIGURATION","qwen3-rerank-v1");}
    public String identity(){return "dashscope-rerank-v1:"+env.getProperty("KNOWLEDGE_RERANK_URL","")+":"+env.getProperty("KNOWLEDGE_RERANK_MODEL","qwen3-rerank");}
    public List<Double> score(String query,List<String> documents){
        if(query==null||query.isBlank()||query.codePointCount(0,query.length())>1000||documents.isEmpty()||documents.size()>20
            ||documents.stream().anyMatch(d->d==null||d.isBlank()||d.codePointCount(0,d.length())>2000)
            ||HybridRanking.tokenUpperBound(query)+documents.stream().mapToInt(HybridRanking::tokenUpperBound).sum()>20000)throw invalid("RERANK_INPUT_LIMIT");
        URI uri=IndexHttp.endpoint(env.getProperty("KNOWLEDGE_RERANK_URL",""),false);
        if(!IndexHttp.loopback(uri)&&!env.getProperty("KNOWLEDGE_EXTERNAL_CALLS_ENABLED",Boolean.class,false))throw invalid("EXTERNAL_CALL_NOT_AUTHORIZED");
        java.util.concurrent.Callable<tools.jackson.databind.JsonNode> call=()->http.send("POST",uri,env.getProperty("KNOWLEDGE_RERANK_API_KEY",""),Map.of("model",env.getProperty("KNOWLEDGE_RERANK_MODEL","qwen3-rerank"),
            "input",Map.of("query",query,"documents",documents),"parameters",Map.of("top_n",documents.size(),"return_documents",false)),
            env.getProperty("KNOWLEDGE_RERANK_TIMEOUT_MS",Long.class,5000L),"RERANK_UNAVAILABLE",true,false);
        var response=budget==null?unchecked(call):budget.invoke("RERANK",local.printer.operations.ExternalCallBudget.bytes(query)+documents.stream().mapToLong(local.printer.operations.ExternalCallBudget::bytes).sum(),call);
        var rows=response.path("output").path("results");if(!rows.isArray()||rows.size()!=documents.size())throw invalid("RERANK_RESPONSE_INVALID");
        var scores=new ArrayList<Double>(Collections.nCopies(documents.size(),null));
        for(var row:rows){var index=row.path("index");var score=row.path("relevance_score");
            if(!index.isIntegralNumber()||!index.canConvertToInt()||index.asInt()<0||index.asInt()>=documents.size()||scores.get(index.asInt())!=null
                ||!score.isNumber()||!Double.isFinite(score.asDouble())||score.asDouble()<0||score.asDouble()>1)throw invalid("RERANK_RESPONSE_INVALID");
            scores.set(index.asInt(),score.asDouble());
        }
        return List.copyOf(scores);
    }
    private static KnowledgeIndexPipeline.IndexFailure invalid(String code){return new KnowledgeIndexPipeline.IndexFailure(code,true,false);}
    private static <T>T unchecked(java.util.concurrent.Callable<T> call){try{return call.call();}catch(RuntimeException e){throw e;}catch(Exception e){throw invalid("RERANK_UNAVAILABLE");}}
}

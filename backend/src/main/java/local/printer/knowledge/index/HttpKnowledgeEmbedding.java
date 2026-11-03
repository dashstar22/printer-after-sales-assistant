package local.printer.knowledge.index;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.knowledge.hybrid.enabled",havingValue="true")
public class HttpKnowledgeEmbedding implements KnowledgeIndexAdapters.Embedding {
    @org.springframework.beans.factory.annotation.Autowired
    private local.printer.operations.ExternalCallBudget budget;
    private final Environment env;private final IndexHttp http=new IndexHttp();
    public HttpKnowledgeEmbedding(Environment env){this.env=env;}
    public String configurationId(){return env.getProperty("KNOWLEDGE_EMBEDDING_CONFIGURATION","embedding-v4-1024-v1");}
    private String model(){return env.getProperty("KNOWLEDGE_EMBEDDING_MODEL","text-embedding-v4");}
    private URI endpoint(){return IndexHttp.endpoint(env.getProperty("KNOWLEDGE_EMBEDDING_BASE_URL","").replaceAll("/+$","")+"/embeddings",false);}
    public int dimensions(){return env.getProperty("KNOWLEDGE_EMBEDDING_DIMENSIONS",Integer.class,1024);}
    public String identity(){return "embedding-http-v1:"+endpoint()+":"+model()+":"+dimensions();}
    @Override public List<List<Double>> embed(List<String> texts){
        if(texts==null||texts.isEmpty()||texts.size()>8||texts.stream().anyMatch(t->t==null||t.isBlank()||t.codePointCount(0,t.length())>2000))throw new KnowledgeIndexPipeline.IndexFailure("EMBEDDING_INPUT_LIMIT",true,false);
        int bytes=texts.stream().mapToInt(t->t.getBytes(StandardCharsets.UTF_8).length).sum();
        if(bytes>48000)throw new KnowledgeIndexPipeline.IndexFailure("EMBEDDING_INPUT_LIMIT",true,false);
        URI url=endpoint();
        if(!IndexHttp.loopback(url)&&!env.getProperty("KNOWLEDGE_EXTERNAL_CALLS_ENABLED",Boolean.class,false))throw new KnowledgeIndexPipeline.IndexFailure("EXTERNAL_CALL_NOT_AUTHORIZED",true,false);
        java.util.concurrent.Callable<tools.jackson.databind.JsonNode> call=()->http.send("POST",url,env.getProperty("KNOWLEDGE_EMBEDDING_API_KEY",""),
            Map.of("model",model(),"dimensions",dimensions(),"encoding_format","float","input",texts),env.getProperty("KNOWLEDGE_EMBEDDING_TIMEOUT_MS",Long.class,10000L),"EMBEDDING_UNAVAILABLE",true,false);
        var response=budget==null?unchecked(call):budget.invoke("EMBEDDING",bytes,call);
        var data=response.path("data");if(!data.isArray()||data.size()!=texts.size())throw invalid();
        var result=new ArrayList<List<Double>>(Collections.nCopies(texts.size(),null));
        for(var row:data){var index=row.path("index");if(!index.isIntegralNumber()||!index.canConvertToInt())throw invalid();int i=index.asInt();
            if(i<0||i>=texts.size()||result.get(i)!=null||!row.path("embedding").isArray())throw invalid();
            var vector=new ArrayList<Double>();for(var value:row.path("embedding")){if(!value.isNumber())throw invalid();vector.add(value.asDouble());}result.set(i,vector);
        }
        try{KnowledgeIndexPipeline.validate(result,texts.size(),dimensions());}catch(RuntimeException e){throw invalid();}return List.copyOf(result);
    }
    private static KnowledgeIndexPipeline.IndexFailure invalid(){return new KnowledgeIndexPipeline.IndexFailure("EMBEDDING_RESPONSE_INVALID",true,true);}
    private static <T>T unchecked(java.util.concurrent.Callable<T> call){try{return call.call();}catch(RuntimeException e){throw e;}catch(Exception e){throw invalid();}}
}

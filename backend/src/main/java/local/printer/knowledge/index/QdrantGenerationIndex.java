package local.printer.knowledge.index;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import static local.printer.knowledge.index.KnowledgeIndexAdapters.*;

@Component
@ConditionalOnProperty(name="app.knowledge.hybrid.enabled",havingValue="true")
public class QdrantGenerationIndex implements KnowledgeIndexAdapters.Vector {
    private final Environment env;private final IndexHttp http=new IndexHttp();
    public QdrantGenerationIndex(Environment env){this.env=env;}
    private int dimensions(){return env.getProperty("KNOWLEDGE_EMBEDDING_DIMENSIONS",Integer.class,1024);}
    private String base(){String value=env.getProperty("app.dependencies.qdrant.base-url","http://127.0.0.1:16333");return IndexHttp.internalEndpoint(value,env).toString().replaceAll("/+$","");}
    private String collection(){String value=env.getProperty("KNOWLEDGE_HYBRID_COLLECTION","");if(!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"))throw new IllegalArgumentException("INDEX_COLLECTION_INVALID");return value;}
    public String identity(){return "qdrant-generation-v1:"+base()+":"+collection()+":"+dimensions();}
    private JsonNode request(String method,String suffix,Object body,boolean missing){return http.send(method,URI.create(base()+"/collections/"+collection()+suffix),null,body,5000,"VECTOR_UNAVAILABLE",false,missing);}
    private void schema(boolean create){
        var response=request("GET","",null,true);
        if(response==null&&create){request("PUT","",Map.of("vectors",Map.of("size",dimensions(),"distance","Cosine")),false);response=request("GET","",null,false);}
        if(response==null)throw failure();var config=response.path("result").path("config").path("params").path("vectors");
        if(config.path("size").asInt()!=dimensions()||!"Cosine".equals(config.path("distance").asString()))throw new KnowledgeIndexPipeline.IndexFailure("VECTOR_SCHEMA_MISMATCH",true,false);
    }
    public static String point(String generation,String chunk){return UUID.nameUUIDFromBytes((generation+":"+chunk).getBytes(StandardCharsets.UTF_8)).toString();}
    @Override public void write(Generation g,List<Chunk> chunks,List<List<Double>> vectors){
        KnowledgeIndexPipeline.validate(vectors,chunks.size(),dimensions());schema(true);var points=new ArrayList<Map<String,Object>>();
        for(int i=0;i<chunks.size();i++){var chunk=chunks.get(i);points.add(Map.of("id",point(g.id(),chunk.id()),"vector",vectors.get(i),"payload",Map.of(
            "generation",g.id(),"chunk",chunk.id(),"config",g.configurationHash(),"manifest",g.manifestHash(),"text_hash",KnowledgeIndexPipeline.digest(chunk.text()),
            "model_scope",g.metadata().get("modelScope"),"models",g.metadata().get("models"))));}
        var response=request("PUT","/points?wait=true",Map.of("points",points),false);
        if(!"completed".equals(response.path("result").path("status").asString()))throw failure();
    }
    @Override public Receipt verify(Generation g){
        schema(false);var expected=new HashMap<String,Chunk>();g.chunks().forEach(c->expected.put(c.id(),c));var ids=new ArrayList<String>();JsonNode offset=null;
        do{
            var body=new LinkedHashMap<String,Object>();body.put("filter",Map.of("must",List.of(match("generation",g.id()))));body.put("limit",128);body.put("with_payload",true);body.put("with_vector",true);
            if(offset!=null&&!offset.isNull())body.put("offset",offset);
            var result=request("POST","/points/scroll",body,false).path("result");var points=result.path("points");if(!points.isArray())throw failure();
            for(var item:points){var p=item.path("payload");String id=p.path("chunk").asString();var c=expected.get(id);
                if(c==null||!point(g.id(),id).equals(item.path("id").asString())||!g.id().equals(p.path("generation").asString())||!g.configurationHash().equals(p.path("config").asString())
                    ||!g.manifestHash().equals(p.path("manifest").asString())||!KnowledgeIndexPipeline.digest(c.text()).equals(p.path("text_hash").asString())
                    ||!Objects.toString(g.metadata().get("modelScope")).equals(p.path("model_scope").asString())||!modelSet(g.metadata().get("models")).equals(modelSet(p.path("models"))))throw invalid();
                var vector=item.path("vector");if(!vector.isArray()||vector.size()!=dimensions())throw invalid();double norm=0;for(var n:vector){if(!n.isNumber()||!Double.isFinite(n.asDouble()))throw invalid();norm+=n.asDouble()*n.asDouble();}if(norm<=0||!Double.isFinite(norm))throw invalid();ids.add(id);
                if(ids.size()>g.chunks().size())throw invalid();
            }
            var next=result.get("next_page_offset");if(next!=null&&!next.isNull()&&(points.isEmpty()||next.equals(offset)))throw invalid();offset=next;
        }while(offset!=null&&!offset.isNull());
        var receipt=new Receipt(g.id(),g.configurationHash(),g.manifestHash(),ids);KnowledgeIndexPipeline.verify(g,receipt);return receipt;
    }
    private static Set<String> modelSet(Object values){var result=new HashSet<String>();if(values instanceof Collection<?> c)c.forEach(v->result.add(v.toString()));else if(values instanceof JsonNode n&&n.isArray())n.forEach(v->result.add(v.asString()));else throw invalid();return result;}
    private static Map<String,Object> match(String key,Object value){return Map.of("key",key,"match",Map.of("value",value));}
    public List<IndexQuery.Hit> search(IndexQuery.Scope scope,List<Double> vector){
        if(scope.generations().isEmpty())return List.of();schema(false);
        var filter=Map.of("must",List.of(Map.of("key","generation","match",Map.of("any",scope.generations()))),
            "should",List.of(match("model_scope","GENERAL"),Map.of("must",List.of(match("model_scope","MODELS"),match("models",scope.model())))));
        var response=request("POST","/points/query",Map.of("query",vector,"filter",filter,"limit",20,"with_payload",true,"with_vector",false,"params",Map.of("exact",true)),false);
        var points=response.path("result").path("points");if(!points.isArray()||points.size()>20)throw invalid();var result=new ArrayList<IndexQuery.Hit>();var seen=new HashSet<String>();
        for(var p:points){var payload=p.path("payload");String generation=payload.path("generation").asString(),chunk=payload.path("chunk").asString();var score=p.path("score");
            if(!scope.generations().contains(generation)||!chunk.matches("[a-f0-9]{64}")||!point(generation,chunk).equals(p.path("id").asString())||!seen.add(chunk)
                ||!score.isNumber()||!Double.isFinite(score.asDouble())||score.asDouble() < -1.00001||score.asDouble()>1.00001)throw invalid();
            result.add(new IndexQuery.Hit(generation,chunk,score.asDouble(),payload.path("config").asString(),payload.path("manifest").asString(),payload.path("text_hash").asString()));
        }
        return result.stream().sorted(Comparator.comparingDouble(IndexQuery.Hit::score).reversed().thenComparing(IndexQuery.Hit::chunkId)).toList();
    }
    private static KnowledgeIndexPipeline.IndexFailure failure(){return new KnowledgeIndexPipeline.IndexFailure("VECTOR_UNAVAILABLE",false,false);}
    private static KnowledgeIndexPipeline.IndexFailure invalid(){return new KnowledgeIndexPipeline.IndexFailure("VECTOR_RESPONSE_INVALID",true,false);}
}

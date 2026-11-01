package local.printer.knowledge;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import local.printer.knowledge.KnowledgeRows.*;
import local.printer.assistant.AssistantExecution;

/** Lazy HTTP protocol shared by explicit publication and read-only retrieval paths. */
final class KnowledgeVectorClient {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    final String collection,model;final int dimensions=1024;
    private final String embedding,key,qdrant;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    KnowledgeVectorClient(Environment env){
        collection=env.getProperty("KNOWLEDGE_VECTOR_COLLECTION","");model=env.getProperty("KNOWLEDGE_EMBEDDING_MODEL","text-embedding-v4");
        key=env.getProperty("KNOWLEDGE_EMBEDDING_API_KEY",env.getProperty("app.dependencies.ai.api-key",""));
        embedding=base(env.getProperty("KNOWLEDGE_EMBEDDING_BASE_URL",env.getProperty("app.dependencies.ai.base-url","")),false);
        qdrant=base(env.getProperty("app.dependencies.qdrant.base-url",""),true);
        if(!collection.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")||!model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")||key.isBlank()||key.contains("\n")||key.contains("\r"))throw invalid();
    }
    private static String base(String value,boolean local){
        try{var uri=URI.create(value);String host=uri.getHost();boolean loop=Set.of("127.0.0.1","localhost","[::1]").contains(host==null?"":host);
            if(uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||host==null
                ||!(uri.getScheme().equals("https")||uri.getScheme().equals("http")&&loop)||local&&!loop)throw invalid();
            return value.replaceAll("/+$","");
        }catch(RuntimeException e){throw invalid();}
    }
    private record Response(int status,JsonNode json){}
    private Response request(String method,String url,Object body,boolean embed,boolean notFound){
        String code=embed?"KNOWLEDGE_EMBEDDING_UNAVAILABLE":"KNOWLEDGE_VECTOR_UNAVAILABLE";
        var scope=AssistantExecution.current();int attempts=scope==null?2:1+scope.readRetries();
        for(int attempt=0;attempt<attempts;attempt++){
            try{
                var timeout=scope==null?Duration.ofSeconds(20):scope.remaining();
                var builder=HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Content-Type","application/json");
                if(embed)builder.header("Authorization","Bearer "+key);
                builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
                var future=http.sendAsync(builder.build(),info->new BoundedBody());HttpResponse<byte[]> response;
                try{response=future.get(scope==null?Duration.ofSeconds(25).toNanos():scope.remaining().toNanos(),TimeUnit.NANOSECONDS);}catch(Exception e){future.cancel(true);throw e;}
                int status=response.statusCode();
                if((status==429||status>=500)&&attempt+1<attempts)continue;
                if(status==404&&notFound)return new Response(status,null);
                if(status<200||status>=300)throw new KnowledgeFailure(code,5);
                JsonNode json;
                try{json=JSON.readTree(response.body());}catch(RuntimeException invalid){throw new KnowledgeFailure(code,5);}
                if(json==null||!json.isObject())throw new KnowledgeFailure(code,5);
                return new Response(status,json);
            }catch(KnowledgeFailure failure){throw failure;
            }catch(Exception failure){
                if(failure instanceof InterruptedException){Thread.currentThread().interrupt();throw new KnowledgeFailure(code,5);}
                if(attempt+1>=attempts||!(failure instanceof java.io.IOException||failure instanceof ExecutionException||failure instanceof TimeoutException))throw new KnowledgeFailure(code,5);
            }
        }throw new KnowledgeFailure(code,5);
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var b:buffers){if((long)bytes.size()+b.remaining()>4*1024*1024){subscription.cancel();result.completeExceptionally(new IllegalStateException());return;}
                byte[] next=new byte[b.remaining()];b.get(next);bytes.writeBytes(next);}
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(new IllegalStateException());}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    void collection(){
        String url=qdrant+"/collections/"+collection;var response=request("GET",url,null,false,true);
        if(response.status()==404){
            // Concurrent creators of different documents may observe the same missing collection.
            try{request("PUT",url,Map.of("vectors",Map.of("size",dimensions,"distance","Cosine")),false,false);}
            catch(KnowledgeFailure failure){if(request("GET",url,null,false,true).status()==404)throw failure;}
            response=request("GET",url,null,false,false);
        }
        var config=response.json().path("result").path("config").path("params").path("vectors");
        if(config.path("size").asInt()!=dimensions||!config.path("distance").asString().equals("Cosine"))throw new KnowledgeFailure("KNOWLEDGE_VECTOR_SCHEMA_MISMATCH",5);
    }
    List<List<Double>> embed(List<Chunk> chunks){
        return embedTexts(chunks.stream().map(Chunk::content).toList());
    }
    private List<List<Double>> embedTexts(List<String> texts){
        var result=request("POST",embedding+"/embeddings",Map.of("model",model,"dimensions",dimensions,"encoding_format","float",
            "input",texts),true,false).json();
        var rows=result.path("data");if(!rows.isArray()||rows.size()!=texts.size())throw vectorsInvalid();
        var vectors=new ArrayList<List<Double>>(Collections.nCopies(texts.size(),null));
        for(var row:rows){var index=row.path("index");if(!index.isIntegralNumber()||!index.canConvertToInt())throw vectorsInvalid();int i=index.asInt(-1);
            if(i<0||i>=vectors.size()||vectors.get(i)!=null)throw vectorsInvalid();vectors.set(i,vector(row.path("embedding")));}
        return vectors;
    }
    List<Double> queryEmbedding(String query){return embedTexts(List.of(query)).get(0);}
    void requireReadableCollection(){
        var response=request("GET",qdrant+"/collections/"+collection,null,false,true);
        if(response.status()==404)throw new KnowledgeFailure("KNOWLEDGE_VECTOR_COLLECTION_MISSING",5);
        var config=response.json().path("result").path("config").path("params").path("vectors");
        if(!config.path("size").isIntegralNumber()||!config.path("size").canConvertToInt()
            ||config.path("size").asInt()!=dimensions||!config.path("distance").asString().equals("Cosine"))
            throw new KnowledgeFailure("KNOWLEDGE_VECTOR_SCHEMA_MISMATCH",5);
    }
    record Candidate(String id,double score,JsonNode payload){}
    List<Candidate> query(List<Double> vector,String modelCode,Collection<String> seen){
        var general=Map.of("key","model_scope","match",Map.of("value","GENERAL"));
        var specific=Map.of("must",List.of(Map.of("key","model_scope","match",Map.of("value","MODELS")),
            Map.of("key","models","match",Map.of("value",modelCode))));
        var filter=new LinkedHashMap<String,Object>();filter.put("should",List.of(general,specific));
        if(!seen.isEmpty())filter.put("must_not",List.of(Map.of("has_id",List.copyOf(seen))));
        var body=Map.of("query",vector,"filter",filter,"limit",20,
            "params",Map.of("exact",true),"with_payload",true,"with_vector",false);
        var points=request("POST",qdrant+"/collections/"+collection+"/points/query?timeout=5",body,false,false).json().path("result").path("points");
        if(!points.isArray()||points.size()>20)throw new KnowledgeFailure("KNOWLEDGE_VECTOR_VERIFY_FAILED",5);
        var result=new ArrayList<Candidate>();
        for(var p:points){
            var score=p.path("score");String id=p.path("id").asString();
            try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}
            catch(RuntimeException invalid){throw new KnowledgeFailure("KNOWLEDGE_VECTOR_VERIFY_FAILED",5);}
            if(!score.isNumber()||!Double.isFinite(score.asDouble())||score.asDouble() < -1.00001||score.asDouble()>1.00001||!p.path("payload").isObject())
                throw new KnowledgeFailure("KNOWLEDGE_VECTOR_VERIFY_FAILED",5);
            result.add(new Candidate(id,score.asDouble(),p.path("payload")));
        }return List.copyOf(result);
    }
    boolean payloadMatches(JsonNode actual,Document d,Chunk c,String layout){
        return actual.equals(JSON.readTree(JSON.writeValueAsBytes(payload(d,c,layout))));
    }
    private List<Double> vector(JsonNode value){
        if(!value.isArray()||value.size()!=dimensions)throw vectorsInvalid();var vector=new ArrayList<Double>();double norm=0;
        for(var n:value){if(!n.isNumber())throw vectorsInvalid();double v=n.asDouble();if(!Double.isFinite(v))throw vectorsInvalid();vector.add(v);norm+=v*v;}
        if(!Double.isFinite(norm)||norm<=0)throw vectorsInvalid();return vector;
    }
    Map<String,Object> payload(Document d,Chunk c,String layout){
        var p=new LinkedHashMap<String,Object>();p.put("document_id",d.id());p.put("chunk_id",c.id());p.put("chunk_index",c.chunkIndex());
        p.put("document_key",d.documentKey());p.put("version",d.version());p.put("content_hash",d.contentHash());p.put("layout_hash",layout);
        p.put("model_scope",d.modelScope());p.put("models",JSON.readTree(d.modelsJson()));p.put("self_authored",d.selfAuthored());p.put("is_simulated",d.simulated());
        p.put("source_type",d.sourceType());p.put("page_number",c.pageNumber());p.put("section",c.section());p.put("embedding_model",model);return p;
    }
    private Map<String,JsonNode> read(List<String> ids){
        var value=request("POST",qdrant+"/collections/"+collection+"/points",Map.of("ids",ids,"with_payload",true,"with_vector",true),false,false).json().path("result");
        if(!value.isArray())throw new KnowledgeFailure("KNOWLEDGE_VECTOR_VERIFY_FAILED",5);
        var points=new HashMap<String,JsonNode>();for(var p:value){String id=p.path("id").asString();if(!ids.contains(id)||points.put(id,p)!=null)throw new KnowledgeFailure("KNOWLEDGE_VECTOR_VERIFY_FAILED",5);}return points;
    }
    private void verify(JsonNode point,Document d,Chunk c,String layout){
        // Compare JSON wire values; MySQL BIGINT IDs may decode as compact JSON integers.
        if(!point.path("payload").equals(JSON.readTree(JSON.writeValueAsBytes(payload(d,c,layout)))))throw new KnowledgeFailure("KNOWLEDGE_VECTOR_VERIFY_FAILED",5);
        vector(point.path("vector"));
    }
    void verifyPublished(String database,Document d,List<Chunk> chunks,String layout){
        var ids=chunks.stream().map(c->KnowledgeIdentity.point(database,d,c,layout)).toList();
        var points=read(ids);
        for(int i=0;i<chunks.size();i++){
            var point=points.get(ids.get(i));
            if(point==null)throw new KnowledgeFailure("KNOWLEDGE_VECTOR_VERIFY_FAILED",5);
            verify(point,d,chunks.get(i),layout);
        }
    }
    int writeAndVerify(String database,Document d,List<Chunk> chunks,String layout,boolean ready){
        collection();int embedded=0;
        for(int start=0;start<chunks.size();start+=8){
            var batch=chunks.subList(start,Math.min(chunks.size(),start+8));var ids=batch.stream().map(c->KnowledgeIdentity.point(database,d,c,layout)).toList();
            var found=read(ids);var missing=new ArrayList<Chunk>();
            for(int i=0;i<batch.size();i++){var p=found.get(ids.get(i));if(p==null)missing.add(batch.get(i));else verify(p,d,batch.get(i),layout);}
            if(!missing.isEmpty()){
                if(ready)throw new KnowledgeFailure("KNOWLEDGE_VECTOR_VERIFY_FAILED",5);
                var vectors=embed(missing);embedded+=missing.size();var points=new ArrayList<Object>();
                for(int i=0;i<missing.size();i++){var c=missing.get(i);points.add(Map.of("id",KnowledgeIdentity.point(database,d,c,layout),"vector",vectors.get(i),"payload",payload(d,c,layout)));}
                var response=request("PUT",qdrant+"/collections/"+collection+"/points?wait=true",Map.of("points",points),false,false).json();
                if(!response.path("result").path("status").asString().equals("completed"))throw new KnowledgeFailure("KNOWLEDGE_VECTOR_VERIFY_FAILED",5);
                found=read(ids);
                for(int i=0;i<batch.size();i++){var p=found.get(ids.get(i));if(p==null)throw new KnowledgeFailure("KNOWLEDGE_VECTOR_VERIFY_FAILED",5);verify(p,d,batch.get(i),layout);}
                // Cosine storage normalizes vectors; compare direction rather than raw magnitude.
                for(int i=0;i<missing.size();i++){var original=vectors.get(i);var stored=vector(found.get(KnowledgeIdentity.point(database,d,missing.get(i),layout)).path("vector"));
                    double norm=Math.sqrt(original.stream().mapToDouble(v->v*v).sum());for(int j=0;j<dimensions;j++)if(Math.abs(original.get(j)/norm-stored.get(j))>0.00001)throw new KnowledgeFailure("KNOWLEDGE_VECTOR_VERIFY_FAILED",5);}
            }
        }return embedded;
    }
    private static KnowledgeFailure vectorsInvalid(){return new KnowledgeFailure("KNOWLEDGE_EMBEDDING_INVALID",5);}
    private static KnowledgeFailure invalid(){return KnowledgeFailure.invalid("KNOWLEDGE_VECTOR_CONFIG_INVALID");}
}

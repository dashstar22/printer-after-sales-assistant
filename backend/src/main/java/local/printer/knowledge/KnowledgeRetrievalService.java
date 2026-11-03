package local.printer.knowledge;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import local.printer.order.OrderService;
import local.printer.knowledge.KnowledgeRows.*;
import local.printer.knowledge.KnowledgeProcessingMapper.Processing;
import local.printer.knowledge.KnowledgeVectorClient.Candidate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Internal read-only retrieval; callers must supply identity from the trusted session. */
@Service
public class KnowledgeRetrievalService {
    @org.springframework.beans.factory.annotation.Autowired
    private local.printer.operations.ExternalCallBudget externalBudget;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final KnowledgeMapper mapper;private final KnowledgeProcessingMapper processing;
    private final OrderService orders;private final Environment env;private final DataSource ds;private final TransactionTemplate read;
    private HybridKnowledgeRetrieval hybrid;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void hybrid(HybridKnowledgeRetrieval value){this.hybrid=value;}
    public KnowledgeRetrievalService(KnowledgeMapper mapper,KnowledgeProcessingMapper processing,OrderService orders,
        Environment env,DataSource ds,PlatformTransactionManager tm){
        this.mapper=mapper;this.processing=processing;this.orders=orders;this.env=env;this.ds=ds;
        read=new TransactionTemplate(tm);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);read.setTimeout(15);
    }
    public record Source(String pointId,long documentId,long chunkId,int chunkIndex,Double score,String content,String title,
        String documentKey,String version,String modelScope,List<String> models,Integer pageNumber,String section,String sourceType,
        String source,String sourceUrl,String officialIndex,String termsUrl,String useCondition,boolean selfAuthored,boolean simulated){
        @Override public String toString(){return "Source[REDACTED]";}
    }
    public record Result(String status,String reason,int candidates,int rejected,int pages,boolean truncated,double minScore,List<Source> sources,Map<String,Object> trace){
        public Result(String status,String reason,int candidates,int rejected,int pages,boolean truncated,double minScore,List<Source> sources){this(status,reason,candidates,rejected,pages,truncated,minScore,sources,Map.of("configuration","legacy-vector-v1"));}
        @Override public String toString(){return "Result[status="+status+", sources="+sources.size()+"]";}
    }
    public Result searchOwned(long customerId,long orderId,String query){
        // No client-supplied model, user name, or role changes this ownership lookup.
        var order=orders.detail(customerId,orderId);return searchModel(order.modelCode(),query);
    }
    /** Chat is a separate disclosure purpose: embedding permission alone never authorizes it. */
    public Result searchOwnedForAssistant(long customerId,long orderId,String query){
        var result=searchOwned(customerId,orderId,query);
        if(hybrid!=null)return result; // Managed path has already checked exact purpose/configuration grants and revalidated its snapshot.
        return chatAllowed(result);
    }
    private Result chatAllowed(Result result){
        if(!"HIT".equals(result.status()))return result;
        var allowed=result.sources().stream().filter(s->{
            var d=mapper.documentById(s.documentId());
            return chatAllowed(d);
        }).toList();
        return new Result(allowed.isEmpty()?"EVIDENCE_NOT_AUTHORIZED":"HIT",allowed.isEmpty()?"CHAT_PERMISSION_REQUIRED":"MATCHED",
            result.candidates(),result.rejected(),result.pages(),result.truncated(),result.minScore(),allowed);
    }
    private boolean chatAllowed(Document d){
        var permitted=new HashSet<>(Arrays.asList(env.getProperty("KNOWLEDGE_CHAT_ALLOWED_HASHES","").split(",")));
        return d!=null&&d.active()&&"READY".equals(d.status())&&d.externalUploadAllowed()
            &&d.contentHash()!=null&&permitted.contains(d.contentHash());
    }
    /** Definite business-rule topics use published chapter identity, not synthetic vector scores. */
    public Result ruleOwnedForAssistant(long customerId,long orderId,RuleTopicCatalog.Topic topic){
        var order=orders.detail(customerId,orderId);Objects.requireNonNull(topic);
        if(hybrid!=null){var managed=hybrid.rule(order.modelCode(),topic);if(!"RULE_TOPIC_UNAVAILABLE".equals(managed.reason()))return managed;}
        var client=new KnowledgeVectorClient(env,externalBudget);client.requireReadableCollection();
        String database;
        try(var connection=ds.getConnection()){database=connection.getCatalog();}
        catch(Exception failure){throw new KnowledgeFailure("KNOWLEDGE_DATABASE_ERROR",3);}
        try{return read.execute(tx->{
            var documents=mapper.activeDocuments(topic.documentKey());
            if(documents.isEmpty())return ruleResult("NO_EVIDENCE","RULE_TOPIC_UNAVAILABLE",List.of());
            if(documents.size()!=1)throw new KnowledgeFailure("KNOWLEDGE_RETRIEVAL_BINDING_MISMATCH",4);
            if(!chatAllowed(documents.get(0)))return ruleResult("EVIDENCE_NOT_AUTHORIZED","CHAT_PERMISSION_REQUIRED",List.of());
            var snapshot=snapshot(documents.get(0).id(),order.modelCode(),client,new long[]{0});
            if(!snapshot.valid())return ruleResult("NO_EVIDENCE","RULE_TOPIC_UNAVAILABLE",List.of());
            var selected=snapshot.chunks().values().stream().filter(c->topic.section().equals(c.section())).sorted(Comparator.comparingInt(Chunk::chunkIndex)).toList();
            if(selected.isEmpty()||selected.size()>5)return ruleResult("NO_EVIDENCE","RULE_CHAPTER_UNAVAILABLE",List.of());
            var d=snapshot.document();var p=snapshot.binding();
            client.verifyPublished(database,d,selected,p.layoutHash());
            var sources=selected.stream().map(c->source(KnowledgeIdentity.point(database,d,c,p.layoutHash()),d,c,null)).toList();
            var allowed=chatAllowed(ruleResult("HIT","RULE_TOPIC_MATCH",sources));
            return "HIT".equals(allowed.status())?ruleResult("HIT","RULE_TOPIC_MATCH",allowed.sources()):allowed;
        });}catch(KnowledgeFailure failure){throw failure;}
        catch(RuntimeException failure){throw new KnowledgeFailure("KNOWLEDGE_DATABASE_ERROR",3);}
    }
    private Result ruleResult(String status,String reason,List<Source> sources){
        return new Result(status,reason,sources.size(),0,0,false,threshold(),sources);
    }
    private double threshold(){
        double value;
        try{value=Double.parseDouble(env.getProperty("KNOWLEDGE_RETRIEVAL_MIN_SCORE","0.5"));}
        catch(NumberFormatException e){throw KnowledgeFailure.invalid("KNOWLEDGE_VECTOR_CONFIG_INVALID");}
        if(!Double.isFinite(value)||value < -1||value>1)throw KnowledgeFailure.invalid("KNOWLEDGE_VECTOR_CONFIG_INVALID");return value;
    }
    Result searchModel(String modelCode,String query){
        if(hybrid!=null){String selected=modelCode,text=query;return hybrid.search(modelCode,query,ids->chatAllowed(searchLegacyModel(selected,text,ids)));}
        return searchLegacyModel(modelCode,query,null);
    }
    private Result searchLegacyModel(String modelCode,String query,Set<Long> allowedDocuments){
        modelCode=text(modelCode,64);query=text(query,1000);
        if(modelCode.codePoints().anyMatch(Character::isISOControl))throw invalid();
        double threshold;
        try{threshold=Double.parseDouble(env.getProperty("KNOWLEDGE_RETRIEVAL_MIN_SCORE","0.5"));}
        catch(NumberFormatException e){throw KnowledgeFailure.invalid("KNOWLEDGE_VECTOR_CONFIG_INVALID");}
        if(!Double.isFinite(threshold)||threshold < -1||threshold>1)throw KnowledgeFailure.invalid("KNOWLEDGE_VECTOR_CONFIG_INVALID");
        var client=new KnowledgeVectorClient(env,externalBudget);String database;
        try(var connection=ds.getConnection()){database=connection.getCatalog();}
        catch(Exception failure){throw new KnowledgeFailure("KNOWLEDGE_DATABASE_ERROR",3);}
        client.requireReadableCollection();var vector=client.queryEmbedding(query);
        var found=new LinkedHashMap<String,Candidate>();int pages=0;boolean truncated=false;
        for(int offset=0;offset<80;offset+=20){
            var batch=allowedDocuments==null?client.query(vector,modelCode,found.keySet()):client.query(vector,modelCode,found.keySet(),allowedDocuments);pages++;
            for(var candidate:batch)found.putIfAbsent(candidate.id(),candidate);
            if(batch.size()<20)break;
            if(offset==60)truncated=true;
        }
        String selectedModel=modelCode;boolean limited=truncated;int pageCount=pages;double min=threshold;
        try{return read.execute(s->validate(database,selectedModel,client,found.values(),pageCount,limited,min));}
        catch(KnowledgeFailure failure){throw failure;}
        catch(RuntimeException failure){throw new KnowledgeFailure("KNOWLEDGE_DATABASE_ERROR",3);}
    }
    private record Snapshot(Document document,Processing binding,Map<Long,Chunk> chunks,boolean valid){}
    private Result validate(String database,String model,KnowledgeVectorClient client,Collection<Candidate> candidates,int pages,boolean limited,double min){
        var cache=new HashMap<Long,Snapshot>();var accepted=new HashMap<Long,Source>();int rejected=0,above=0;long[] total={0};
        for(var candidate:candidates){
            Long documentId=positive(candidate.payload().path("document_id")),chunkId=positive(candidate.payload().path("chunk_id"));
            // A configured model/collection mismatch is an error even below the provisional score.
            var snapshot=documentId==null?null:cache.computeIfAbsent(documentId,id->snapshot(id,model,client,total));
            if(candidate.score()<min)continue;above++;
            if(documentId==null||chunkId==null){rejected++;continue;}
            var d=snapshot.document();var p=snapshot.binding();var chunk=snapshot.chunks().get(chunkId);
            if(!snapshot.valid()||chunk==null||!KnowledgeIdentity.point(database,d,chunk,p.layoutHash()).equals(candidate.id())
                ||!client.payloadMatches(candidate.payload(),d,chunk,p.layoutHash())){rejected++;continue;}
            accepted.putIfAbsent(chunkId,source(candidate.id(),d,chunk,candidate.score()));
        }
        var sources=accepted.values().stream().sorted(Comparator.comparingDouble(Source::score).reversed().thenComparing(Source::pointId)).limit(5).toList();
        String reason=!sources.isEmpty()?"MATCHED":limited?"CANDIDATE_LIMIT":candidates.isEmpty()?"EMPTY":above==0?"BELOW_THRESHOLD":"FILTERED";
        return new Result(sources.isEmpty()?"NO_EVIDENCE":"HIT",reason,candidates.size(),rejected,pages,limited,min,sources);
    }
    private static Source source(String point,Document d,Chunk c,Double score){
        return new Source(point,d.id(),c.id(),c.chunkIndex(),score,c.content(),d.title(),d.documentKey(),d.version(),
            d.modelScope(),models(d),c.pageNumber(),c.section(),d.sourceType(),d.selfAuthored()?"自编模拟企业规则":d.sourceUrl(),
            d.sourceUrl(),d.officialIndex(),d.termsUrl(),d.useCondition(),d.selfAuthored(),d.simulated());
    }
    private Snapshot snapshot(long id,String model,KnowledgeVectorClient client,long[] total){
        var d=mapper.documentById(id);
        if(d==null||!d.status().equals("READY")||!d.active())return new Snapshot(d,null,Map.of(),false);
        if(!applicable(d,model))return new Snapshot(d,null,Map.of(),false);
        var p=processing.processing(id);
        if(p==null||p.publishedAt()==null||p.failureCode()!=null||d.contentHash()==null||!d.contentHash().matches("[0-9a-f]{64}"))return new Snapshot(d,p,Map.of(),false);
        if(!p.model().equals(client.model)||p.dimensions()!=client.dimensions||!p.collectionName().equals(client.collection))
            throw new KnowledgeFailure("KNOWLEDGE_RETRIEVAL_BINDING_MISMATCH",4);
        if(mapper.chunkCount(id)>10_000)throw new KnowledgeFailure("KNOWLEDGE_RETRIEVAL_LIMIT",4);
        var chunks=mapper.chunks(id);var byId=new HashMap<Long,Chunk>();boolean valid=!chunks.isEmpty();
        for(int i=0;i<chunks.size();i++){
            var c=chunks.get(i);int length=c.content().codePointCount(0,c.content().length());total[0]+=length;
            if(total[0]>8_000_000)throw new KnowledgeFailure("KNOWLEDGE_RETRIEVAL_LIMIT",4);
            if(c.chunkIndex()!=i||c.documentId()!=id||length>2000||c.content().isBlank()||byId.put(c.id(),c)!=null)valid=false;
        }
        valid=valid&&KnowledgeIdentity.metadata(d).equals(p.metadataHash())&&KnowledgeIdentity.layout(d,chunks,p.chunkSize(),p.chunkOverlap()).equals(p.layoutHash());
        return new Snapshot(d,p,byId,valid);
    }
    private static List<String> models(Document d){
        var node=JSON.readTree(d.modelsJson());if(!node.isArray())return null;var result=new ArrayList<String>();
        for(var n:node){if(!n.isString())return null;result.add(n.asString());}return List.copyOf(result);
    }
    private static boolean applicable(Document d,String model){
        var models=models(d);return models!=null&&(d.modelScope().equals("GENERAL")&&models.isEmpty()||d.modelScope().equals("MODELS")&&models.contains(model));
    }
    private static Long positive(JsonNode value){return value.isIntegralNumber()&&value.canConvertToLong()&&value.asLong()>0?value.asLong():null;}
    static String text(String input,int max){
        if(input==null)throw invalid();String result=KnowledgeTextExtractor.normalize(input);
        int count=result.codePointCount(0,result.length());
        if(count==0||count>max||result.codePoints().anyMatch(c->c==0||c>=0xD800&&c<=0xDFFF))throw invalid();return result;
    }
    private static KnowledgeFailure invalid(){return KnowledgeFailure.invalid("KNOWLEDGE_QUERY_INVALID");}
}

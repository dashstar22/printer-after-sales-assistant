package local.printer.knowledge;

import java.util.*;
import java.util.function.Function;
import local.printer.knowledge.index.*;
import local.printer.knowledge.index.IndexQuery.Hit;
import local.printer.knowledge.KnowledgeGenerationReader.*;
import local.printer.knowledge.KnowledgeRetrievalService.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="app.knowledge.hybrid.enabled",havingValue="true")
public class HybridKnowledgeRetrieval {
    public static final String VECTOR="vector-v1",RRF="hybrid-rrf-v1",RERANK="hybrid-rerank-v1";
    private final KnowledgeGenerationReader reader;private final LuceneKnowledgeIndex bm25;private final QdrantGenerationIndex vector;
    private final HttpKnowledgeEmbedding embedding;private final HttpKnowledgeReranker reranker;private final Environment env;private final JdbcTemplate db;
    public HybridKnowledgeRetrieval(KnowledgeGenerationReader reader,LuceneKnowledgeIndex bm25,QdrantGenerationIndex vector,HttpKnowledgeEmbedding embedding,HttpKnowledgeReranker reranker,Environment env,JdbcTemplate db){
        this.reader=reader;this.bm25=bm25;this.vector=vector;this.embedding=embedding;this.reranker=reranker;this.env=env;this.db=db;
    }
    private KnowledgeCandidateCache cache;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void cache(KnowledgeCandidateCache value){cache=value;}
    private Snapshot snapshot(String model){
        var snapshot=reader.snapshot(model,embedding.configurationId(),reranker.configurationId(),env.getProperty("KNOWLEDGE_CHAT_CONFIGURATION","chat-v1"));
        for(var e:snapshot.entries().values()){
            String expected=KnowledgeIndexPipeline.digest(e.revisionConfiguration(),embedding.configurationId(),embedding.identity(),embedding.dimensions(),vector.identity(),bm25.identity());
            if(!expected.equals(e.generation().configurationHash())||!embedding.configurationId().equals(e.embeddingConfiguration()))throw new KnowledgeFailure("KNOWLEDGE_RETRIEVAL_BINDING_MISMATCH",4);
        }
        return snapshot;
    }
    public boolean hasManagedKnowledge(){return db.queryForObject("SELECT COUNT(*) FROM knowledge_active_generations WHERE generation_id IS NOT NULL",Integer.class)>0;}
    private Set<Long> legacyIds(String model){
        var result=new HashSet<Long>();for(var r:db.queryForList("""
            SELECT id,model_scope,models_json FROM knowledge_documents d WHERE d.active=TRUE AND d.status='READY'
            AND d.layout_version='knowledge-layout-v1'
            AND NOT EXISTS(SELECT 1 FROM knowledge_index_generations g WHERE g.document_id=d.id AND g.status='PUBLISHED')
            AND (d.model_scope='GENERAL' OR JSON_CONTAINS(d.models_json,JSON_QUOTE(?)))
            """,model))result.add(((Number)r.get("id")).longValue());return Set.copyOf(result);
    }
    private String legacyFingerprint(Set<Long> ids){
        var values=new ArrayList<Object>();
        for(long id:new TreeSet<>(ids)){
            values.add(db.queryForList("SELECT d.*,p.model,p.dimensions,p.collection_name,p.metadata_hash,p.layout_hash,p.failure_code,p.published_at FROM knowledge_documents d JOIN knowledge_processing p ON p.document_id=d.id WHERE d.id=?",id).stream().map(TreeMap::new).toList());
            values.add(db.queryForList("SELECT id,chunk_index,content,page_number,section FROM knowledge_chunks WHERE document_id=? AND revision=1 ORDER BY chunk_index",id).stream().map(TreeMap::new).toList());
        }
        return KnowledgeIndexPipeline.digest(values.toArray());
    }
    public Result search(String model,String query,Function<Set<Long>,Result> legacy){
        model=KnowledgeRetrievalService.text(model,64);query=KnowledgeRetrievalService.text(query,1000);
        if(model.codePoints().anyMatch(Character::isISOControl))throw KnowledgeFailure.invalid("KNOWLEDGE_QUERY_INVALID");
        String configuration=env.getProperty("KNOWLEDGE_RETRIEVAL_CONFIGURATION",RERANK);
        double vectorMin=threshold("KNOWLEDGE_HYBRID_VECTOR_MIN",0.5),rerankMin=threshold("KNOWLEDGE_RERANK_MIN",0.2);
        if(!Set.of(VECTOR,RRF,RERANK).contains(configuration))throw new KnowledgeFailure("KNOWLEDGE_RETRIEVAL_CONFIG_INVALID",2);
        if(reader.conflict(model,query))return result("MODEL_CLARIFICATION_REQUIRED","MODEL_CONFLICT",List.of(),trace(configuration,-1,0,"NOT_RUN","NOT_RUN","NOT_RUN",List.of(),null));
        for(int attempt=0;attempt<2;attempt++){
            Snapshot snapshot=snapshot(model);var legacyScope=legacyIds(model);String legacyState=legacyFingerprint(legacyScope);var sources=new HashMap<String,Source>();var entries=new HashMap<String,Entry>();boolean legacyChatDenied=false;
            snapshot.entries().values().forEach(e->e.generation().chunks().forEach(c->entries.put(c.id(),e)));
            var v=new ArrayList<Hit>();var b=new ArrayList<Hit>();String vStatus="OK",bStatus=VECTOR.equals(configuration)?"NOT_REQUESTED":"OK",rStatus="NOT_REQUESTED";
            String cacheKey=cache==null||!legacyScope.isEmpty()?null:cache.key(model,query,snapshot.fingerprint(),configuration,
                Long.toString(snapshot.epoch()),embedding.configurationId(),embedding.identity(),reranker.configurationId(),env.getProperty("KNOWLEDGE_CHAT_CONFIGURATION","chat-v1"),Double.toString(vectorMin),Double.toString(rerankMin),LuceneKnowledgeIndex.QUERY_POLICY);
            var cached=cacheKey==null?null:cache.get(cacheKey);
            if(cached!=null){v.addAll(cached.vector());b.addAll(cached.bm25());vStatus="CACHED";bStatus=VECTOR.equals(configuration)?"NOT_REQUESTED":"CACHED";}
            if(cached==null){
            if(!VECTOR.equals(configuration))try{b.addAll(bm25.search(snapshot.scope(),query));}catch(RuntimeException unavailable){bStatus="UNAVAILABLE";}
            try{
                var allowed=new HashSet<String>();snapshot.entries().forEach((id,e)->{if(e.grants().get("embedding"))allowed.add(id);});
                if(!allowed.isEmpty())v.addAll(vector.search(new IndexQuery.Scope(model,allowed),embedding.embed(List.of(query)).get(0)));
                if(allowed.size()!=snapshot.entries().size())vStatus="PERMISSION_LIMITED";
            }catch(RuntimeException unavailable){vStatus="UNAVAILABLE";}
            }
            // Legacy data keeps its own old collection, identity and permission rules; never re-embeds its bodies.
            if(!legacyScope.isEmpty())try{
                var old=legacy.apply(legacyScope);legacyChatDenied="EVIDENCE_NOT_AUTHORIZED".equals(old.status());for(var source:old.sources()){String id="legacy:"+source.pointId();sources.put(id,source);v.add(new Hit("legacy",id,source.score()==null?0:source.score()));}
            }catch(RuntimeException unavailable){vStatus="UNAVAILABLE";}
            int vectorRejected=v.size(),bm25Rejected=b.size();
            v=validated(v,entries,sources,snapshot);b=validated(b,entries,sources,snapshot);
            vectorRejected-=v.size();bm25Rejected-=b.size();
            if(vectorRejected>0)vStatus="BINDING_REJECTED";if(bm25Rejected>0)bStatus="BINDING_REJECTED";
            String vResult=vStatus,bResult=bStatus;
            // Both lanes are sorted independently before RRF. A failed lane's partial results are not accepted as complete.
            v.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::chunkId));
            if(v.size()>20)v=new ArrayList<>(v.subList(0,20));
            var ranked=HybridRanking.fuse(v,b);var admission=new ArrayList<HybridRanking.Ranked>();boolean chatDenied=legacyChatDenied;
            for(var row:ranked){
                var entry=entries.get(row.chunkId());
                if(entry!=null&&!entry.grants().get("chat")){chatDenied=true;continue;}
                // This is the versioned development policy, not a threshold on the RRF sum.
                if(row.bm25Rank()==null&&(row.vectorScore()==null||row.vectorScore()<vectorMin))continue;
                if(admission.size()<20)admission.add(row);
            }
            if(!snapshot.fingerprint().equals(snapshot(model).fingerprint())||!legacyScope.equals(legacyIds(model))||!legacyState.equals(legacyFingerprint(legacyScope)))continue;
            boolean excerpt=false;
            if(RERANK.equals(configuration)&&!admission.isEmpty()){
                var eligible=new ArrayList<HybridRanking.Ranked>();int bytes=HybridRanking.tokenUpperBound(query);
                for(var row:admission){var entry=entries.get(row.chunkId());if(entry==null||!entry.grants().get("rerank")){excerpt=true;continue;}
                    String body=sources.get(row.chunkId()).content();int size=HybridRanking.tokenUpperBound(body);if(bytes+size>20000)continue;bytes+=size;eligible.add(row);}
                boolean completeRerankInput=eligible.size()==admission.size();
                if(!completeRerankInput)rStatus="PERMISSION_OR_INPUT_LIMITED";
                if(!eligible.isEmpty())try{
                    var scores=reranker.score(query,eligible.stream().map(r->sources.get(r.chunkId()).content()).toList());
                    var reranked=new ArrayList<HybridRanking.Ranked>();for(int i=0;i<eligible.size();i++)if(scores.get(i)>=rerankMin)reranked.add(eligible.get(i).reranked(scores.get(i)));
                    reranked.sort(Comparator.comparingDouble((HybridRanking.Ranked r)->r.rerankScore()).reversed().thenComparing(HybridRanking.Ranked::chunkId));admission=reranked;
                    if(completeRerankInput)rStatus="OK";
                }catch(RuntimeException unavailable){rStatus="UNAVAILABLE";excerpt=true;}
                else {rStatus="NOT_AUTHORIZED";excerpt=true;}
            }
            if(!snapshot.fingerprint().equals(snapshot(model).fingerprint())||!legacyScope.equals(legacyIds(model))||!legacyState.equals(legacyFingerprint(legacyScope)))continue;
            var units=admission.stream().map(r->{var s=sources.get(r.chunkId());return new HybridRanking.Unit(r.chunkId(),s.title(),Objects.toString(s.section(),""),s.content(),KnowledgeEvidenceContext.serialized(s),KnowledgeEvidenceContext.deduplicationScope(s));}).toList();
            var context=HybridRanking.context(units,5744); // 256 bytes reserved for tool envelope, separators and status/reason.
            // Current reply contract is 4000 characters. Leave room for headings without cutting any evidence unit.
            var selected=new ArrayList<Source>();int display=200;
            for(var unit:context.units()){var s=sources.get(unit.chunkId());int size=s.content().codePointCount(0,s.content().length())+s.title().length()+Objects.toString(s.useCondition(),"").length()+String.join("、",s.models()).length()+200;
                if(display+size<=4000){selected.add(s);display+=size;}}
            var trace=new LinkedHashMap<>(trace(configuration,snapshot.epoch(),attempt+1,vResult,bResult,rStatus,ranked,context));
            trace.put("cache",cacheKey==null?"BYPASS":cached==null?"MISS":"HIT");
            if(cacheKey!=null&&cached==null&&"OK".equals(vResult)&&Set.of("OK","NOT_REQUESTED").contains(bResult))cache.put(cacheKey,v,b);
            trace.put("rejectedCandidates",Map.of("vector",vectorRejected,"bm25",bm25Rejected));
            trace.put("admittedRanking",List.copyOf(admission));trace.put("returnedPointIds",selected.stream().map(Source::pointId).toList());
            trace.put("vectorMinimum",vectorMin);trace.put("rerankMinimum",rerankMin);trace.put("contextBudget",6000);
            boolean bothFailed="UNAVAILABLE".equals(vResult)&&(VECTOR.equals(configuration)||"UNAVAILABLE".equals(bResult));
            if(bothFailed)return result("RETRIEVAL_UNAVAILABLE","BOTH_LANES_FAILED",List.of(),trace);
            boolean degraded="UNAVAILABLE".equals(vResult)||"UNAVAILABLE".equals(bResult)||"PERMISSION_LIMITED".equals(vResult)||"BINDING_REJECTED".equals(vResult)||"BINDING_REJECTED".equals(bResult);
            if(selected.isEmpty()&&chatDenied)return result("EVIDENCE_NOT_AUTHORIZED","CHAT_PERMISSION_REQUIRED",List.of(),trace);
            if(selected.isEmpty())return result("NO_EVIDENCE",degraded?"DEGRADED_NO_EVIDENCE":"ADMISSION_REJECTED",List.of(),trace);
            return result(excerpt?"EXCERPT_ONLY":degraded?"DEGRADED":"HIT",excerpt?"RERANK_NOT_COMPLETE":degraded?"SINGLE_LANE":"MATCHED",selected,trace);
        }
        return result("RETRIEVAL_UNAVAILABLE","KNOWLEDGE_CHANGED_TWICE",List.of(),trace(configuration,-1,2,"STALE","STALE","NOT_RUN",List.of(),null));
    }
    private ArrayList<Hit> validated(List<Hit> hits,Map<String,Entry> entries,Map<String,Source> sources,Snapshot snapshot){
        var result=new ArrayList<Hit>();var seen=new HashSet<String>();
        for(var hit:hits){if(!seen.add(hit.chunkId()))continue;if(hit.chunkId().startsWith("legacy:")){if(sources.containsKey(hit.chunkId()))result.add(hit);continue;}
            var entry=entries.get(hit.chunkId());if(entry==null||!entry.generation().id().equals(hit.generation())||!snapshot.entries().containsKey(hit.generation())||!Double.isFinite(hit.score()))continue;
            var chunk=entry.generation().chunks().stream().filter(c->c.id().equals(hit.chunkId())).findFirst().orElseThrow();
            if(!entry.generation().configurationHash().equals(hit.configuration())||!entry.generation().manifestHash().equals(hit.manifest())||!KnowledgeIndexPipeline.digest(chunk.text()).equals(hit.textHash()))continue;
            sources.put(hit.chunkId(),reader.source(entry,hit.chunkId(),hit.score()));result.add(hit);}
        return result;
    }
    public Result rule(String model,RuleTopicCatalog.Topic topic){
        for(int attempt=0;attempt<2;attempt++){
            var snapshot=snapshot(model);var found=new ArrayList<Source>();boolean denied=false,managed=false;
            for(var entry:snapshot.entries().values())if(entry.generation().documentKey().equals(topic.documentKey())){
                managed=true;if(!entry.grants().get("chat")){denied=true;continue;}
                // Direct lookup still verifies the published derived indexes, without query embedding/rerank/model calls.
                vector.verify(entry.generation());bm25.verify(entry.generation());
                entry.generation().chunks().stream().filter(c->topic.section().equals(c.section())).forEach(c->found.add(reader.source(entry,c.id(),null)));
            }
            if(!snapshot.fingerprint().equals(snapshot(model).fingerprint()))continue;
            if(found.isEmpty()&&denied)return result("EVIDENCE_NOT_AUTHORIZED","CHAT_PERMISSION_REQUIRED",List.of(),Map.of("method","RULE_TOPIC_DIRECT"));
            if(found.size()>5)return result("NO_EVIDENCE","RULE_CHAPTER_UNAVAILABLE",List.of(),Map.of("method","RULE_TOPIC_DIRECT"));
            return result(found.isEmpty()?"NO_EVIDENCE":"HIT",found.isEmpty()?(managed?"RULE_CHAPTER_UNAVAILABLE":"RULE_TOPIC_UNAVAILABLE"):"RULE_TOPIC_MATCH",found,Map.of("method","RULE_TOPIC_DIRECT"));
        }
        return result("RETRIEVAL_UNAVAILABLE","KNOWLEDGE_CHANGED_TWICE",List.of(),Map.of("method","RULE_TOPIC_DIRECT"));
    }
    private static Map<String,Object> trace(String config,long epoch,int attempts,String vector,String bm25,String rerank,List<HybridRanking.Ranked> rows,HybridRanking.Context context){
        var trace=new LinkedHashMap<String,Object>();trace.put("configuration",config);trace.put("admission","dev-v1");trace.put("epoch",epoch);trace.put("snapshotAttempts",attempts);
        trace.put("bm25QueryPolicy",LuceneKnowledgeIndex.QUERY_POLICY);
        trace.put("contextSelectionPolicy","source-applicability-dedup-v2");
        trace.put("vector",vector);trace.put("bm25",bm25);trace.put("rerank",rerank);trace.put("ranking",rows);trace.put("rrfK",60);trace.put("scoreMeaning","ranking_only");
        if(context!=null){trace.put("contextTokenUpperBound",context.tokenUpperBound());trace.put("contextLimited",context.limited());trace.put("duplicateOf",context.duplicateOf());trace.put("selectedChunkIds",context.units().stream().map(HybridRanking.Unit::chunkId).toList());}return Map.copyOf(trace);
    }
    private static Result result(String status,String reason,List<Source> sources,Map<String,Object> trace){return new Result(status,reason,sources.size(),0,0,false,0,List.copyOf(sources),trace);}
    private double threshold(String key,double fallback){double value=env.getProperty(key,Double.class,fallback);if(!Double.isFinite(value)||value<0||value>1)throw new KnowledgeFailure("KNOWLEDGE_RETRIEVAL_CONFIG_INVALID",2);return value;}
}

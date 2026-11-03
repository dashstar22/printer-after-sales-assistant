package local.printer.knowledge;

import java.util.*;
import local.printer.knowledge.index.*;
import static local.printer.knowledge.index.KnowledgeIndexAdapters.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** MySQL is authoritative; indexes return only identities. Each read uses a consistent short snapshot. */
@Component
public class KnowledgeGenerationReader {
    private final JdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate read;private final KnowledgePurposePolicy policy;
    public KnowledgeGenerationReader(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager tm,KnowledgePurposePolicy policy){
        this.db=db;this.json=json;this.policy=policy;read=new TransactionTemplate(tm);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Entry(Generation generation,JsonNode metadata,Map<String,Long> rowIds,Map<String,Boolean> grants,String revisionConfiguration,String embeddingConfiguration) {}
    public record Snapshot(long epoch,String model,Map<String,Entry> entries,String fingerprint) {
        public IndexQuery.Scope scope(){return new IndexQuery.Scope(model,entries.keySet());}
    }
    public Snapshot snapshot(String model,String embedding,String rerank,String chat){return read.execute(tx->{
        long epoch=db.queryForObject("SELECT value FROM knowledge_epoch WHERE id=1",Long.class);var entries=new TreeMap<String,Entry>();var identity=new ArrayList<Object>();identity.add(epoch);
        var rows=db.queryForList("""
            SELECT g.*,r.metadata_json,r.content_hash,r.config_hash AS revision_config,r.layout_version,d.document_key,d.active,d.status AS document_status
            FROM knowledge_active_generations a JOIN knowledge_index_generations g ON g.generation_id=a.generation_id
            JOIN knowledge_documents d ON d.id=g.document_id JOIN knowledge_revisions r ON r.document_id=g.document_id AND r.revision=g.revision
            WHERE g.status='PUBLISHED' AND d.active=TRUE AND d.status='READY'
            """);
        if(rows.size()>2048)throw new KnowledgeFailure("KNOWLEDGE_RETRIEVAL_LIMIT",4);
        long chars=0;
        for(var row:rows){
            String id=row.get("generation_id").toString();long document=((Number)row.get("document_id")).longValue();int revision=((Number)row.get("revision")).intValue();
            var metadata=json.readTree(row.get("metadata_json").toString());var models=metadata.path("models");
            boolean applies="GENERAL".equals(metadata.path("modelScope").asString())&&models.isArray()&&models.isEmpty()
                ||"MODELS".equals(metadata.path("modelScope").asString())&&models.isArray()&&java.util.stream.StreamSupport.stream(models.spliterator(),false).anyMatch(v->v.isString()&&model.equals(v.asString()));
            if(!applies)continue;
            var grants=new TreeMap<String,Boolean>();
            for(var purpose:Map.of("embedding",embedding,"rerank",rerank,"chat",chat).entrySet()){
                boolean permitted=false;try{policy.require(metadata.path("processingPermissions"),purpose.getKey(),purpose.getValue());
                    permitted=db.queryForObject("SELECT COUNT(*) FROM knowledge_permissions WHERE document_id=? AND revision=? AND purpose=? AND allowed=TRUE AND configuration_id=?",Integer.class,document,revision,purpose.getKey(),purpose.getValue())==1;
                }catch(local.printer.administration.AdminFailure denied){}
                grants.put(purpose.getKey(),permitted);
            }
            var chunks=new ArrayList<Chunk>();var rowIds=new HashMap<String,Long>();
            for(var c:db.queryForList("SELECT * FROM knowledge_chunks WHERE document_id=? AND revision=? ORDER BY chunk_index",document,revision)){
                String chunk=Objects.toString(c.get("stable_chunk_id"),"");String text=c.get("content").toString();chars+=text.length();
                if(chars>8_000_000||chunks.size()>=10000||!chunk.matches("[a-f0-9]{64}")||((Number)c.get("chunk_index")).intValue()!=chunks.size()||text.isBlank()||text.codePointCount(0,text.length())>2000)throw new KnowledgeFailure("KNOWLEDGE_RETRIEVAL_BINDING_MISMATCH",4);
                chunks.add(new Chunk(chunk,chunks.size(),text,(Integer)c.get("page_number"),(String)c.get("section")));rowIds.put(chunk,((Number)c.get("id")).longValue());
            }
            @SuppressWarnings("unchecked") Map<String,Object> data=json.convertValue(metadata,Map.class);
            var g=new Generation(id,document,revision,row.get("layout_hash").toString(),row.get("config_hash").toString(),row.get("manifest_hash").toString(),row.get("document_key").toString(),data,List.copyOf(chunks));
            if(chunks.size()!=((Number)row.get("chunk_count")).intValue()||chunks.isEmpty()||!g.manifestHash().equals(KnowledgeIndexPipeline.manifest(chunks)))throw new KnowledgeFailure("KNOWLEDGE_RETRIEVAL_BINDING_MISMATCH",4);
            identity.add(id);identity.add(g.manifestHash());identity.add(metadata);identity.add(grants);
            entries.put(id,new Entry(g,metadata,Map.copyOf(rowIds),Map.copyOf(grants),row.get("revision_config").toString(),row.get("embedding_configuration").toString()));
        }
        return new Snapshot(epoch,model,Map.copyOf(entries),KnowledgeIndexPipeline.digest(identity.toArray()));
    });}
    public boolean conflict(String model,String query){
        String normalized=IndexQuery.normalized(query);String chosen=local.printer.administration.AdminInput.modelKey(model);var mentioned=new HashSet<String>();
        for(var row:db.queryForList("SELECT model_code FROM device_models")){
            String code=row.get("model_code").toString();if(containsCode(normalized,code))mentioned.add(local.printer.administration.AdminInput.modelKey(code));
        }
        for(var row:db.queryForList("SELECT a.original_alias,m.model_code FROM model_aliases a JOIN device_models m ON m.id=a.model_id"))
            if(containsCode(normalized,row.get("original_alias").toString()))mentioned.add(local.printer.administration.AdminInput.modelKey(row.get("model_code").toString()));
        return mentioned.stream().anyMatch(v->!v.equals(chosen));
    }
    static boolean containsCode(String text,String code){return java.util.regex.Pattern.compile("(?<![A-Z0-9])"+java.util.regex.Pattern.quote(IndexQuery.normalized(code))+"(?![A-Z0-9])").matcher(IndexQuery.normalized(text)).find();}
    public KnowledgeRetrievalService.Source source(Entry entry,String chunkId,Double score){
        var g=entry.generation();var c=g.chunks().stream().filter(v->v.id().equals(chunkId)).findFirst().orElseThrow();var m=entry.metadata();var provenance=m.path("source");var models=new ArrayList<String>();m.path("models").forEach(v->models.add(v.asString()));
        String sourceType=m.path("sourceType").asString();boolean official="official_manual".equals(sourceType);boolean simulated=m.path("isSimulated").asBoolean();
        String label=official?provenance.path("downloadUrl").asString():simulated?"自编模拟企业规则":"enterprise_faq".equals(sourceType)?"企业正式FAQ":"企业正式规则";
        return new KnowledgeRetrievalService.Source(QdrantGenerationIndex.point(g.id(),chunkId),g.documentId(),entry.rowIds().get(chunkId),c.index(),score,c.text(),m.path("title").asString(),g.documentKey(),m.path("version").asString(),
            m.path("modelScope").asString(),List.copyOf(models),c.page(),c.section(),sourceType,label,official?provenance.path("downloadUrl").asString():null,
            official?provenance.path("officialIndex").asString():null,official?provenance.path("termsUrl").asString():null,m.path("useCondition").asString(),!official,simulated);
    }
}

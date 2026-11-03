package local.printer.knowledge.index;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import local.printer.administration.AdminFailure;
import local.printer.knowledge.KnowledgePurposePolicy;
import static local.printer.knowledge.index.KnowledgeIndexAdapters.*;
import local.printer.knowledge.index.KnowledgeIndexAdapters.Vector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Persisted phase boundary. F23 verifies real adapters separately; missing adapters fail closed. */
@Service
public class KnowledgeIndexPipeline {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final KnowledgePurposePolicy permissions;
    private final List<Embedding> embeddings;
    private final List<Vector> vectors;
    private final List<Bm25> bm25s;
    public KnowledgeIndexPipeline(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager tm,KnowledgePurposePolicy permissions,
            List<Embedding> embeddings,List<Vector> vectors,List<Bm25> bm25s) {
        this.db=db;this.json=json;this.tx=new TransactionTemplate(tm);this.permissions=permissions;
        this.embeddings=List.copyOf(embeddings);this.vectors=List.copyOf(vectors);this.bm25s=List.copyOf(bm25s);
    }
    public static class IndexFailure extends RuntimeException {
        private final String code;private final boolean permanent,unknownCost;
        public IndexFailure(String code,boolean permanent,boolean unknownCost) {super(code);this.code=code;this.permanent=permanent;this.unknownCost=unknownCost;}
        public String code(){return code;} public boolean permanent(){return permanent;} public boolean unknownCost(){return unknownCost;}
    }
    private static IndexFailure permanent(String code){return new IndexFailure(code,true,false);}
    private Map<String,Object> row(String sql,Object... args){var rows=db.queryForList(sql,args);if(rows.size()!=1)throw permanent("INDEX_SOURCE_MISSING");return rows.get(0);}
    private static String string(Map<String,Object> r,String k){return Objects.toString(r.get(k),"");}
    private static int number(Map<String,Object> r,String k){return ((Number)r.get(k)).intValue();}
    private static boolean yes(Map<String,Object> r,String k){return Boolean.TRUE.equals(r.get(k)) || r.get(k) instanceof Number n && n.intValue()!=0;}
    private static <T> T only(List<T> values){if(values.size()!=1)throw permanent("INDEX_ADAPTER_MISSING");return values.get(0);}
    public void process(String jobId,long documentId,int revision,Runnable fence) {
        try(var scope=local.printer.operations.ExternalCallBudget.scope("job:"+jobId,"TASK",environment==null?120000:environment.getProperty("EXTERNAL_TASK_TIMEOUT_MS",Long.class,120000L))){processBounded(jobId,documentId,revision,fence);}
    }
    private void processBounded(String jobId,long documentId,int revision,Runnable fence) {
        fence.run();var request=row("SELECT binding_hash FROM knowledge_publication_requests WHERE id=? AND document_id=? AND revision=?",jobId,documentId,revision);
        Embedding embedding=only(embeddings);Vector vector=only(vectors);Bm25 bm25=only(bm25s);
        var source=row("SELECT r.*,d.document_key FROM knowledge_revisions r JOIN knowledge_documents d ON d.id=r.document_id WHERE r.document_id=? AND r.revision=?",documentId,revision);
        if("DISABLED".equals(source.get("stage")))throw permanent("INDEX_GENERATION_DISABLED");
        if(!source.get("binding_hash").equals(request.get("binding_hash")))throw permanent("INDEX_SOURCE_CHANGED");
        checkPermission(source,embedding.configurationId());
        var chunkRows=db.queryForList("SELECT stable_chunk_id,chunk_index,content,page_number,section FROM knowledge_chunks WHERE document_id=? AND revision=? ORDER BY chunk_index",documentId,revision);
        var chunks=new ArrayList<Chunk>();
        for(var c:chunkRows){String id=string(c,"stable_chunk_id");if(!id.matches("[a-f0-9]{64}") || number(c,"chunk_index")!=chunks.size())throw permanent("INDEX_CHUNK_IDENTITY_INVALID");
            chunks.add(new Chunk(id,chunks.size(),string(c,"content"),(Integer)c.get("page_number"),(String)c.get("section")));}
        if(chunks.isEmpty() || chunks.size()!=number(source,"chunk_count") || chunks.size()>10000)throw permanent("INDEX_CHUNK_IDENTITY_INVALID");
        String config=digest(source.get("config_hash"),embedding.configurationId(),embedding.identity(),embedding.dimensions(),vector.identity(),bm25.identity());
        String manifest=manifest(chunks),id=digest(documentId,revision,source.get("layout_hash"),config);
        @SuppressWarnings("unchecked") Map<String,Object> metadata=json.convertValue(json.readTree(string(source,"metadata_json")),Map.class);
        var generation=new Generation(id,documentId,revision,string(source,"layout_hash"),config,manifest,string(source,"document_key"),metadata,List.copyOf(chunks));
        tx.executeWithoutResult(s->{fence.run();db.update("""
            INSERT IGNORE INTO knowledge_index_generations(generation_id,document_id,revision,binding_hash,layout_hash,config_hash,
            manifest_hash,chunk_count,embedding_configuration,status,vector_verified,bm25_verified)
            VALUES(?,?,?,?,?,?,?,?,?,'INDEXING',FALSE,FALSE)
            """,id,documentId,revision,source.get("binding_hash"),source.get("layout_hash"),config,manifest,chunks.size(),embedding.configurationId());
            var existing=row("SELECT * FROM knowledge_index_generations WHERE generation_id=? FOR UPDATE",id);
            if(!manifest.equals(existing.get("manifest_hash")) || !source.get("binding_hash").equals(existing.get("binding_hash")))throw permanent("INDEX_MANIFEST_CONFLICT");});
        var persisted=row("SELECT * FROM knowledge_index_generations WHERE generation_id=?",id);
        if("DISABLED".equals(persisted.get("status")))throw permanent("INDEX_GENERATION_DISABLED");
        if(Set.of("PUBLISHED","RETIRED").contains(string(persisted,"status")))return;
        if(!yes(persisted,"vector_verified")) {
            checkpoint(jobId,"EMBEDDING",fence);
            for(int start=0;start<chunks.size();start+=8) {
                final int batch=start/8;var part=chunks.subList(start,Math.min(start+8,chunks.size()));
                var saved=tx.execute(s->{fence.run();db.update("INSERT IGNORE INTO knowledge_index_batches(generation_id,batch_number,status) VALUES(?,?,'PENDING')",id,batch);
                    return row("SELECT * FROM knowledge_index_batches WHERE generation_id=? AND batch_number=? FOR UPDATE",id,batch);});
                String status=string(saved,"status");
                // A previous process may have died after supplier acceptance. Repeating can incur another charge.
                if(status.equals("CALLING") || status.equals("UNKNOWN")) {
                    tx.executeWithoutResult(s->{fence.run();db.update("UPDATE knowledge_index_batches SET status='UNKNOWN',failure_code='EMBEDDING_RESULT_UNKNOWN' WHERE generation_id=? AND batch_number=?",id,batch);});
                    throw new IndexFailure("EMBEDDING_RESULT_UNKNOWN",true,true);
                }
                List<List<Double>> values;
                if(saved.get("embedding_json")!=null)values=readVectors(string(saved,"embedding_json"),part.size(),embedding.dimensions());
                else {
                    tx.executeWithoutResult(s->{fence.run();checkPermission(source,embedding.configurationId());db.update("UPDATE knowledge_index_batches SET status='CALLING',failure_code=NULL WHERE generation_id=? AND batch_number=?",id,batch);});
                    try {values=embedding.embed(part.stream().map(Chunk::text).toList());validate(values,part.size(),embedding.dimensions());}
                    catch(local.printer.operations.ExternalCallBudget.Limit failure) {
                        tx.executeWithoutResult(s->{fence.run();db.update("UPDATE knowledge_index_batches SET status='PENDING',failure_code='EXTERNAL_BUDGET_EXHAUSTED' WHERE generation_id=? AND batch_number=?",id,batch);});
                        throw failure;
                    } catch(IndexFailure failure) {
                        tx.executeWithoutResult(s->{fence.run();db.update("UPDATE knowledge_index_batches SET status=?,failure_code=? WHERE generation_id=? AND batch_number=?",failure.unknownCost()?"UNKNOWN":"PENDING",failure.code(),id,batch);});throw failure;
                    } catch(RuntimeException failure) {
                        tx.executeWithoutResult(s->{fence.run();db.update("UPDATE knowledge_index_batches SET status='UNKNOWN',failure_code='EMBEDDING_RESULT_UNKNOWN' WHERE generation_id=? AND batch_number=?",id,batch);});
                        throw new IndexFailure("EMBEDDING_RESULT_UNKNOWN",true,true);
                    }
                    String result=json.writeValueAsString(values);
                    tx.executeWithoutResult(s->{fence.run();db.update("UPDATE knowledge_index_batches SET status='EMBEDDED',embedding_json=? WHERE generation_id=? AND batch_number=?",result,id,batch);});
                }
                if(!status.equals("VECTOR_WRITTEN")) {
                    checkpoint(jobId,"VECTOR_INDEXING",fence);
                    fence.run();vector.write(generation,part,values);fence.run();
                    tx.executeWithoutResult(s->{fence.run();db.update("UPDATE knowledge_index_batches SET status='VECTOR_WRITTEN' WHERE generation_id=? AND batch_number=?",id,batch);});
                }
            }
            fence.run();verify(generation,vector.verify(generation));fence.run();
            tx.executeWithoutResult(s->{fence.run();db.update("UPDATE knowledge_index_generations SET vector_verified=TRUE WHERE generation_id=?",id);});
        }
        if(!yes(persisted,"bm25_verified")) {
            checkpoint(jobId,"BM25_INDEXING",fence);
            fence.run();bm25.write(generation);fence.run();verify(generation,bm25.verify(generation));fence.run();
            tx.executeWithoutResult(s->{fence.run();db.update("UPDATE knowledge_index_generations SET bm25_verified=TRUE,status='VERIFIED' WHERE generation_id=?",id);});
        }
        checkpoint(jobId,"VERIFYING",fence);
        // Saved success avoids repeated writes/calls, but storage may have been lost meanwhile.
        // Re-read both manifests immediately before the short activation transaction.
        fence.run();verify(generation,vector.verify(generation));fence.run();
        verify(generation,bm25.verify(generation));fence.run();
        checkpoint(jobId,"ACTIVATING",fence);
        activate(generation,embedding.configurationId(),source,fence);
    }
    private void checkpoint(String jobId,String stage,Runnable fence) {
        tx.executeWithoutResult(s->{fence.run();
            db.update("UPDATE knowledge_jobs SET stage=?,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND state='RUNNING'",stage,jobId);
            db.update("""
                UPDATE knowledge_job_attempts a JOIN knowledge_jobs j ON j.id=a.job_id AND j.cycle=a.cycle AND j.attempt=a.attempt
                SET a.stage=? WHERE j.id=? AND j.state='RUNNING' AND a.lease_token=j.lease_token
                """,stage,jobId);
        });
    }
    private void checkPermission(Map<String,Object> source,String configuration) {
        try {permissions.require(json.readTree(string(source,"metadata_json")).get("processingPermissions"),"embedding",configuration);}
        catch(AdminFailure e){throw permanent("INDEX_PERMISSION_DENIED");}
        int permitted=db.queryForObject("SELECT COUNT(*) FROM knowledge_permissions WHERE document_id=? AND revision=? AND purpose='embedding' AND allowed=TRUE AND configuration_id=?",Integer.class,source.get("document_id"),source.get("revision"),configuration);
        if(permitted!=1)throw permanent("INDEX_PERMISSION_DENIED");
    }
    private void activate(Generation generation,String configuration,Map<String,Object> source,Runnable fence) {
        tx.executeWithoutResult(s->{
            fence.run(); // Caller locks the job row in this transaction to fence expired workers.
            db.queryForObject("SELECT value FROM knowledge_epoch WHERE id=1 FOR UPDATE",Long.class);
            var current=row("SELECT * FROM knowledge_documents WHERE id=? FOR UPDATE",generation.documentId());
            var revision=row("SELECT * FROM knowledge_revisions WHERE document_id=? AND revision=? FOR UPDATE",generation.documentId(),generation.revision());
            db.queryForList("SELECT purpose FROM knowledge_permissions WHERE document_id=? AND revision=? ORDER BY purpose FOR UPDATE",generation.documentId(),generation.revision());
            var ready=row("SELECT * FROM knowledge_index_generations WHERE generation_id=? FOR UPDATE",generation.id());
            if(Set.of("PUBLISHED","RETIRED").contains(string(ready,"status")))return;
            if(number(current,"revision")!=generation.revision() || !revision.get("binding_hash").equals(source.get("binding_hash")))throw permanent("INDEX_SOURCE_CHANGED");
            if("DISABLED".equals(revision.get("stage")) || "DISABLED".equals(ready.get("status")))throw permanent("INDEX_GENERATION_DISABLED");
            if(!yes(ready,"vector_verified") || !yes(ready,"bm25_verified") || !generation.manifestHash().equals(ready.get("manifest_hash")))throw permanent("INDEX_NOT_VERIFIED");
            checkPermission(revision,configuration);
            var metadata=json.readTree(string(revision,"metadata_json"));
            var provenance=metadata.path("source");boolean official=metadata.path("sourceType").asString().equals("official_manual");
            // All uses must still match the previewed grants; publication never broadens another use.
            for(String purpose:List.of("rerank","chat")) {
                var grant=metadata.path("processingPermissions").path(purpose);String allowedConfig=grant.path("configurationId").asString();
                try{permissions.require(metadata.get("processingPermissions"),purpose,allowedConfig);}catch(AdminFailure e){throw permanent("INDEX_PERMISSION_DENIED");}
                if(db.queryForObject("SELECT COUNT(*) FROM knowledge_permissions WHERE document_id=? AND revision=? AND purpose=? AND allowed=TRUE AND configuration_id=?",Integer.class,generation.documentId(),generation.revision(),purpose,allowedConfig)!=1)throw permanent("INDEX_PERMISSION_DENIED");
            }
            // Preserve one active version per document family, with history left intact.
            db.update("UPDATE knowledge_index_generations g JOIN knowledge_documents d ON d.id=g.document_id SET g.status='RETIRED' WHERE d.document_key=? AND g.status='PUBLISHED'",generation.documentKey());
            db.update("UPDATE knowledge_documents SET active=FALSE WHERE document_key=? AND active=TRUE",generation.documentKey());
            db.update("""
                UPDATE knowledge_documents SET status='READY',active=TRUE,content_hash=?,title=?,version=?,source_type=?,self_authored=?,is_simulated=?,
                model_scope=?,models_json=?,use_condition=?,distribution_allowed=?,external_upload_allowed=TRUE,source_url=?,official_index=?,terms_url=?,
                brand=?,region=?,language=?,acquired_on=?,checked_on=?,provenance_json=?,storage_key=?,local_file=?,layout_version=?,version_lock=version_lock+1 WHERE id=?
                """,revision.get("content_hash"),metadata.path("title").asString(),metadata.path("version").asString(),metadata.path("sourceType").asString(),
                !official,metadata.path("isSimulated").asBoolean(),metadata.path("modelScope").asString(),
                metadata.path("models").toString(),metadata.path("useCondition").asString(),metadata.path("distributionAllowed").asBoolean(),
                official?provenance.path("downloadUrl").asString():null,official?provenance.path("officialIndex").asString():null,official?provenance.path("termsUrl").asString():null,
                official?provenance.path("brand").asString():null,official?provenance.path("region").asString():null,official?provenance.path("language").asString():null,
                official?provenance.path("acquiredOn").asString():null,provenance.path("checkedOn").asString(),provenance.toString(),revision.get("storage_key"),
                revision.get("storage_key"),revision.get("layout_version"),generation.documentId());
            db.update("INSERT INTO knowledge_active_generations(document_key,generation_id) VALUES(?,?) ON DUPLICATE KEY UPDATE generation_id=VALUES(generation_id)",generation.documentKey(),generation.id());
            db.update("UPDATE knowledge_index_generations SET status='PUBLISHED',published_at=UTC_TIMESTAMP(6) WHERE generation_id=?",generation.id());
            db.update("UPDATE knowledge_revisions SET stage='PUBLISHED' WHERE document_id=? AND revision=?",generation.documentId(),generation.revision());
            db.update("UPDATE knowledge_epoch SET value=value+1 WHERE id=1");
        });
    }
    /** Call only inside an authorized audited administrative transaction. */
    public void disable(long documentId,int revision) {
        tx.executeWithoutResult(s->{
            db.queryForObject("SELECT value FROM knowledge_epoch WHERE id=1 FOR UPDATE",Long.class);
            row("SELECT document_key FROM knowledge_documents WHERE id=? FOR UPDATE",documentId);
            row("SELECT revision FROM knowledge_revisions WHERE document_id=? AND revision=? FOR UPDATE",documentId,revision);
            db.update("UPDATE knowledge_index_generations SET status='DISABLED' WHERE document_id=? AND revision=? AND status<>'DISABLED'",documentId,revision);
            var removed=db.update("UPDATE knowledge_active_generations a JOIN knowledge_index_generations g ON g.generation_id=a.generation_id SET a.generation_id=NULL WHERE g.document_id=? AND g.revision=?",documentId,revision);
            if(removed>0)db.update("UPDATE knowledge_documents SET active=FALSE,version_lock=version_lock+1 WHERE id=?",documentId);
            db.update("UPDATE knowledge_revisions SET stage='DISABLED' WHERE document_id=? AND revision=?",documentId,revision);
            db.update("UPDATE knowledge_epoch SET value=value+1 WHERE id=1");
        });
    }
    public long epoch(){return db.queryForObject("SELECT value FROM knowledge_epoch WHERE id=1",Long.class);}
    public List<Map<String,Object>> results(long documentId){return db.queryForList("SELECT generation_id,revision,layout_hash,config_hash,manifest_hash,chunk_count,status,vector_verified,bm25_verified,created_at,published_at FROM knowledge_index_generations WHERE document_id=? ORDER BY created_at",documentId);}
    public List<Map<String,Object>> batches(long documentId){return db.queryForList("SELECT b.generation_id,b.batch_number,b.status,b.failure_code,(b.embedding_json IS NOT NULL) AS embedding_saved FROM knowledge_index_batches b JOIN knowledge_index_generations g ON g.generation_id=b.generation_id WHERE g.document_id=? ORDER BY g.created_at,b.batch_number",documentId);}
    public static void verify(Generation generation,Receipt actual) {
        if(actual==null || !generation.id().equals(actual.generationId()) || !generation.configurationHash().equals(actual.configurationHash()) || !generation.manifestHash().equals(actual.manifestHash()) || actual.chunkIds()==null
            || actual.chunkIds().size()!=generation.chunks().size() || new HashSet<>(actual.chunkIds()).size()!=actual.chunkIds().size()
            || !new HashSet<>(actual.chunkIds()).equals(new HashSet<>(generation.chunks().stream().map(Chunk::id).toList())))throw permanent("INDEX_VERIFICATION_FAILED");
    }
    private List<List<Double>> readVectors(String saved,int count,int dimensions) {
        var node=json.readTree(saved);var result=new ArrayList<List<Double>>();
        if(!node.isArray())throw permanent("INDEX_EMBEDDING_INVALID");
        for(var vector:node){if(!vector.isArray())throw permanent("INDEX_EMBEDDING_INVALID");var values=new ArrayList<Double>();for(var n:vector){if(!n.isNumber())throw permanent("INDEX_EMBEDDING_INVALID");values.add(n.asDouble());}result.add(values);}
        validate(result,count,dimensions);return result;
    }
    static void validate(List<List<Double>> vectors,int count,int dimensions) {
        if(dimensions<1 || dimensions>4096 || vectors==null || vectors.size()!=count)throw permanent("INDEX_EMBEDDING_INVALID");
        for(var vector:vectors){if(vector==null || vector.size()!=dimensions)throw permanent("INDEX_EMBEDDING_INVALID");double norm=0;for(var v:vector){if(v==null || !Double.isFinite(v))throw permanent("INDEX_EMBEDDING_INVALID");norm+=v*v;}if(norm<=0 || !Double.isFinite(norm))throw permanent("INDEX_EMBEDDING_INVALID");}
    }
    public static String manifest(List<Chunk> chunks){var values=new ArrayList<Object>();for(var c:chunks){values.add(c.id());values.add(c.index());values.add(c.text());values.add(c.page());values.add(c.section());}return digest(values.toArray());}
    public static String digest(Object... values){try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);for(Object value:values){if(value==null){out.writeInt(-1);continue;}byte[] encoded=value.toString().getBytes(StandardCharsets.UTF_8);out.writeInt(encoded.length);out.write(encoded);}return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));}catch(Exception e){throw new IllegalStateException(e);}}
}

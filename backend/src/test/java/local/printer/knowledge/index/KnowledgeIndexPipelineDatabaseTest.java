package local.printer.knowledge.index;

import static org.junit.jupiter.api.Assertions.*;
import static local.printer.knowledge.index.KnowledgeIndexAdapters.*;
import local.printer.knowledge.index.KnowledgeIndexAdapters.Vector;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.printer.knowledge.KnowledgePurposePolicy;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** Explicit opt-in real MySQL tests. Derived index implementations are deliberately local fixtures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeIndexPipelineDatabaseTest {
    JdbcTemplate db;JdbcTransactionManager tm;JsonMapper json=JsonMapper.builder().build();KnowledgePurposePolicy permissions;long actor;
    @BeforeAll void connect(){
        String url=System.getenv("PHASE3_DB_URL");
        Assumptions.assumeTrue(url!=null && !url.isBlank(),"UNVERIFIED: PHASE3_DB_URL must name an isolated migrated schema");
        assertTrue(url.contains("phase3") || url.contains("printer_p3_test"),"Only an explicitly phase3 schema may receive fixtures");
        var ds=new DriverManagerDataSource(url,System.getenv("PHASE3_DB_USER"),System.getenv("PHASE3_DB_PASSWORD"));db=new JdbcTemplate(ds);tm=new JdbcTransactionManager(ds);
        permissions=new KnowledgePurposePolicy(new MockEnvironment().withProperty("app.knowledge.processing-configurations","embedding:fixture,rerank:fixture,chat:fixture"));
        var actors=db.queryForList("SELECT id FROM users ORDER BY id LIMIT 1");
        if(actors.isEmpty()){
            db.update("INSERT INTO users(username,password_hash,role) VALUES(?,'unused-fixture-hash','ADMIN')","phase3-index-"+UUID.randomUUID());
            actor=db.queryForObject("SELECT id FROM users WHERE username LIKE 'phase3-index-%' ORDER BY id DESC LIMIT 1",Long.class);
        }else actor=((Number)actors.get(0).get("id")).longValue();
    }
    record Fixture(long id,int revision,String job,String key) {}
    Fixture fixture(int count){
        String key="p3-index-"+UUID.randomUUID();
        db.update("""
            INSERT INTO knowledge_documents(document_key,version,title,source_type,self_authored,is_simulated,model_scope,models_json,
            checked_on,use_condition,distribution_allowed,external_upload_allowed,local_file,provenance_json,layout_version)
            VALUES(?,'v1','合成测试','self_authored_simulation',TRUE,TRUE,'GENERAL','[]','2026-11-01','test',FALSE,TRUE,'fixture','{}','knowledge-layout-v2')
            """,key);
        long id=db.queryForObject("SELECT id FROM knowledge_documents WHERE document_key=?",Long.class,key);
        return revision(id,key,1,count);
    }
    Fixture revision(long id,String key,int revision,int count){
        String hash=KnowledgeIndexPipeline.digest(id,revision);String job=UUID.randomUUID().toString();
        var metadata=Map.of("version","v"+revision,"title","合成测试","sourceType","self_authored_simulation","isSimulated",true,"modelScope","GENERAL","models",List.of(),"useCondition","test","distributionAllowed",false,"source",Map.of("checkedOn","2026-11-01","description","local synthetic fixture"),
                "processingPermissions",Map.of("embedding",Map.of("allowed",true,"configurationId","fixture"),"rerank",Map.of("allowed",true,"configurationId","fixture"),"chat",Map.of("allowed",true,"configurationId","fixture")));
        db.update("""
            INSERT INTO knowledge_revisions(document_id,revision,storage_key,file_name,metadata_json,content_hash,layout_hash,config_hash,binding_hash,layout_version,extracted_text,chunk_count,created_by)
            VALUES(?,?,?,'fixture.md',?,?,?,?,?,'knowledge-layout-v2','合成测试',?,?)
            """,id,revision,"fixture-"+hash,json.writeValueAsString(metadata),hash,hash,hash,hash,count,actor);
        for(int i=0;i<count;i++)db.update("INSERT INTO knowledge_chunks(document_id,revision,chunk_index,stable_chunk_id,content,section,layout_version) VALUES(?,?,?,?,?,'合成','knowledge-layout-v2')",id,revision,i,KnowledgeIndexPipeline.digest(id,revision,i),"警告：合成数据步骤"+i);
        for(String purpose:List.of("embedding","rerank","chat"))db.update("INSERT INTO knowledge_permissions(document_id,revision,purpose,allowed,configuration_id) VALUES(?,?,?,TRUE,'fixture')",id,revision,purpose);
        db.update("INSERT INTO knowledge_publication_requests(id,document_id,revision,binding_hash,actor_id) VALUES(?,?,?,?,?)",job,id,revision,hash,actor);
        db.update("UPDATE knowledge_documents SET revision=? WHERE id=?",revision,id);
        return new Fixture(id,revision,job,key);
    }
    static class Ports implements Embedding,Vector,Bm25 {
        final AtomicInteger embedCalls=new AtomicInteger(),vectorWrites=new AtomicInteger(),bm25Writes=new AtomicInteger(),verifyCalls=new AtomicInteger();
        int invalidAt=-1;
        boolean failVector,failBm25,wrongReceipt,unknown;Runnable duringBm25=()->{};
        public String identity(){return "local-fixture-v1";}public String configurationId(){return "fixture";}public int dimensions(){return 2;}
        public List<List<Double>> embed(List<String> texts){embedCalls.incrementAndGet();if(unknown)throw new IllegalStateException("lost response");return texts.stream().map(t->List.of(1.0,2.0)).toList();}
        public void write(Generation g,List<Chunk> c,List<List<Double>> e){vectorWrites.incrementAndGet();if(failVector)throw new KnowledgeIndexPipeline.IndexFailure("VECTOR_UNAVAILABLE",false,false);}
        public void write(Generation g){bm25Writes.incrementAndGet();duringBm25.run();if(failBm25)throw new KnowledgeIndexPipeline.IndexFailure("BM25_UNAVAILABLE",false,false);}
        public Receipt verify(Generation g){boolean invalid=verifyCalls.incrementAndGet()==invalidAt;return new Receipt(g.id(),wrongReceipt || invalid?"wrong":g.configurationHash(),g.manifestHash(),g.chunks().stream().map(Chunk::id).toList());}
    }
    KnowledgeIndexPipeline pipeline(Ports ports){return new KnowledgeIndexPipeline(db,json,tm,permissions,List.of(ports),List.of(ports),List.of(ports));}
    void process(KnowledgeIndexPipeline p,Fixture f){p.process(f.job,f.id,f.revision,()->{});}
    String status(Fixture f){return db.queryForObject("SELECT status FROM knowledge_index_generations WHERE document_id=? AND revision=?",String.class,f.id,f.revision);}
    @Test void partialFailureReusesSavedEmbeddingAndVerifiedVectorAndRetainsOldGeneration(){
        var old=fixture(10);var ports=new Ports();var p=pipeline(ports);long epoch=p.epoch();process(p,old);assertEquals(2,ports.embedCalls.get());assertEquals(epoch+1,p.epoch());
        var next=revision(old.id,old.key,2,10);ports.failBm25=true;
        assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->process(p,next));assertEquals("PUBLISHED",status(old));assertEquals("INDEXING",status(next));
        assertEquals(1,db.queryForObject("SELECT revision FROM knowledge_active_generations a JOIN knowledge_index_generations g ON g.generation_id=a.generation_id WHERE a.document_key=?",Integer.class,old.key));
        int embeddings=ports.embedCalls.get(),writes=ports.vectorWrites.get();ports.failBm25=false;process(p,next);
        assertEquals(embeddings,ports.embedCalls.get());assertEquals(writes,ports.vectorWrites.get());assertEquals("RETIRED",status(old));assertEquals("PUBLISHED",status(next));assertEquals(epoch+2,p.epoch());
        // Recreated service reads the same persisted state; a duplicate completion does not increment epoch.
        var restarted=pipeline(ports);process(restarted,next);assertEquals(epoch+2,restarted.epoch());assertEquals(20,db.queryForObject("SELECT COUNT(*) FROM knowledge_chunks WHERE document_id=?",Integer.class,old.id));
        restarted.disable(next.id,next.revision);assertEquals("DISABLED",status(next));assertEquals(epoch+3,restarted.epoch());assertFalse(db.queryForObject("SELECT active FROM knowledge_documents WHERE id=?",Boolean.class,next.id));
        assertEquals("INDEX_GENERATION_DISABLED",assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->process(restarted,next)).code());
    }
    @Test void invalidReceiptAndMissingAdaptersNeverActivate(){
        var f=fixture(1);var ports=new Ports();ports.wrongReceipt=true;var p=pipeline(ports);long epoch=p.epoch();
        assertEquals("INDEX_VERIFICATION_FAILED",assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->process(p,f)).code());
        assertEquals(epoch,p.epoch());assertFalse(db.queryForObject("SELECT active FROM knowledge_documents WHERE id=?",Boolean.class,f.id));
        var missing=new KnowledgeIndexPipeline(db,json,tm,permissions,List.of(),List.of(),List.of());
        assertEquals("INDEX_ADAPTER_MISSING",assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->process(missing,f)).code());
    }
    @Test void embeddingResponseLossIsPersistedAndCannotLoopOrMasqueradeAsZeroCost(){
        var f=fixture(1);var ports=new Ports();ports.unknown=true;var p=pipeline(ports);
        assertTrue(assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->process(p,f)).unknownCost());
        assertEquals("UNKNOWN",db.queryForObject("SELECT b.status FROM knowledge_index_batches b JOIN knowledge_index_generations g ON g.generation_id=b.generation_id WHERE g.document_id=?",String.class,f.id));
        ports.unknown=false;assertTrue(assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->process(p,f)).unknownCost());assertEquals(1,ports.embedCalls.get());
    }
    @Test void savedEmbeddingSurvivesVectorWriteFailure(){
        var f=fixture(2);var ports=new Ports();ports.failVector=true;var p=pipeline(ports);
        assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->process(p,f));assertEquals(1,ports.embedCalls.get());ports.failVector=false;process(p,f);assertEquals(1,ports.embedCalls.get());assertEquals("PUBLISHED",status(f));
    }
    @Test void concurrentReplacementAndPermissionRevocationCannotActivateStaleGeneration() throws Exception {
        var old=fixture(1);var ports=new Ports();var p=pipeline(ports);process(p,old);var next=revision(old.id,old.key,2,1);
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);ports.duringBm25=()->{entered.countDown();try{assertTrue(resume.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}};
        var executor=Executors.newSingleThreadExecutor();try{
            var work=executor.submit(()->process(p,next));assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertEquals("PUBLISHED",status(old));var third=revision(old.id,old.key,3,1);resume.countDown();
            var failed=assertThrows(ExecutionException.class,()->work.get(10,TimeUnit.SECONDS));assertInstanceOf(KnowledgeIndexPipeline.IndexFailure.class,failed.getCause());assertEquals("PUBLISHED",status(old));
            ports.duringBm25=()->db.update("UPDATE knowledge_permissions SET allowed=FALSE WHERE document_id=? AND revision=? AND purpose='chat'",third.id,third.revision);
            assertEquals("INDEX_PERMISSION_DENIED",assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->process(p,third)).code());assertEquals("PUBLISHED",status(old));
        }finally{resume.countDown();executor.shutdownNow();}
    }
    @Test void delayedRetiredJobCannotReactivateOrIncrementEpoch(){
        var old=fixture(1);var ports=new Ports();var p=pipeline(ports);process(p,old);
        var next=revision(old.id,old.key,2,1);process(p,next);long epoch=p.epoch();int calls=ports.embedCalls.get();
        process(p,old);
        assertEquals("RETIRED",status(old));assertEquals("PUBLISHED",status(next));assertEquals(epoch,p.epoch());assertEquals(calls,ports.embedCalls.get());
        assertEquals(2,db.queryForObject("SELECT g.revision FROM knowledge_active_generations a JOIN knowledge_index_generations g ON g.generation_id=a.generation_id WHERE a.document_key=?",Integer.class,next.key));
    }
    @Test void disabledBeforeIndexCreationCannotRunQueuedWork(){
        var f=fixture(1);var ports=new Ports();var p=pipeline(ports);p.disable(f.id,f.revision);long epoch=p.epoch();
        assertEquals("INDEX_GENERATION_DISABLED",assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->process(p,f)).code());
        assertEquals(epoch,p.epoch());assertEquals(0,ports.embedCalls.get());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_index_generations WHERE document_id=?",Integer.class,f.id));
    }
    @Test void completedStageMustStillReadBackBothManifestsBeforeActivation(){
        var f=fixture(1);var ports=new Ports();ports.failBm25=true;var p=pipeline(ports);
        assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->process(p,f));
        int embeddings=ports.embedCalls.get(),writes=ports.vectorWrites.get();ports.failBm25=false;
        // Retry verifies newly completed BM25 once, then re-verifies the previously completed vector stage.
        ports.invalidAt=ports.verifyCalls.get()+2;
        assertEquals("INDEX_VERIFICATION_FAILED",assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->process(p,f)).code());
        assertFalse(db.queryForObject("SELECT active FROM knowledge_documents WHERE id=?",Boolean.class,f.id));
        assertEquals(embeddings,ports.embedCalls.get());assertEquals(writes,ports.vectorWrites.get());
        ports.invalidAt=-1;process(p,f);assertEquals("PUBLISHED",status(f));
    }
}

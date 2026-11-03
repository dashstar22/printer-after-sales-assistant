package local.printer.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.printer.knowledge.index.*;
import local.printer.knowledge.index.KnowledgeIndexAdapters.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** Real MySQL + disk Lucene + real Qdrant. Supplier protocols are explicitly local synthetic fixtures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridKnowledgeDatabaseTest {
    JdbcTemplate db;JdbcTransactionManager tm;long actor;JsonMapper json=JsonMapper.builder().build();
    HttpServer supplier;String base;MockEnvironment env;LuceneKnowledgeIndex bm25;QdrantGenerationIndex vector;HttpKnowledgeEmbedding embedding;HttpKnowledgeReranker reranker;KnowledgeGenerationReader reader;KnowledgeIndexPipeline pipeline;HybridKnowledgeRetrieval hybrid;
    AtomicInteger embeddings=new AtomicInteger(),reranks=new AtomicInteger();volatile boolean rerankFailure;volatile Runnable onRerank=()->{},onEmbed=()->{};Path directory;
    @BeforeAll void connect(){
        String url=System.getenv("PHASE4_DB_URL");Assumptions.assumeTrue(url!=null,"UNVERIFIED: isolated PHASE4_DB_URL required");assertTrue(url.contains("printer_p4_"));
        var ds=new DriverManagerDataSource(url,System.getenv("PHASE4_DB_USER"),System.getenv("PHASE4_DB_PASSWORD"));db=new JdbcTemplate(ds);tm=new JdbcTransactionManager(ds);
        Flyway.configure().dataSource(ds).cleanDisabled(true).load().migrate();assertFalse(db.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"));
        String name="p4-actor-"+UUID.randomUUID();db.update("INSERT INTO users(username,password_hash,role) VALUES(?,'fixture-unused','ADMIN')",name);actor=db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);
        db.update("INSERT IGNORE INTO device_models(model_code,brand,is_demo) VALUES('P-100','Synthetic',TRUE),('P-200','Synthetic',TRUE)");
    }
    @BeforeEach void fixture() throws Exception {
        db.update("UPDATE knowledge_documents SET active=FALSE");db.update("UPDATE knowledge_active_generations SET generation_id=NULL");embeddings.set(0);reranks.set(0);rerankFailure=false;onRerank=()->{};onEmbed=()->{};
        supplier=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        supplier.createContext("/embeddings",exchange->{embeddings.incrementAndGet();var request=json.readTree(exchange.getRequestBody().readAllBytes());onEmbed.run();var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<request.path("input").size();i++)rows.add(Map.of("index",i,"embedding",List.of(1.0,0.1)));reply(exchange,200,Map.of("data",rows));});
        supplier.createContext("/rerank",exchange->{reranks.incrementAndGet();var request=json.readTree(exchange.getRequestBody().readAllBytes());onRerank.run();if(rerankFailure){reply(exchange,503,Map.of("code","local-injected-timeout"));return;}var rows=new ArrayList<Map<String,Object>>();for(int i=request.path("input").path("documents").size()-1;i>=0;i--)rows.add(Map.of("index",i,"relevance_score",0.9-i*0.01));reply(exchange,200,Map.of("output",Map.of("results",rows)));});
        supplier.start();base="http://127.0.0.1:"+supplier.getAddress().getPort();
        env=new MockEnvironment().withProperty("KNOWLEDGE_EMBEDDING_BASE_URL",base).withProperty("KNOWLEDGE_RERANK_URL",base+"/rerank").withProperty("KNOWLEDGE_EMBEDDING_DIMENSIONS","2")
            .withProperty("KNOWLEDGE_EMBEDDING_CONFIGURATION","fixture").withProperty("KNOWLEDGE_RERANK_CONFIGURATION","fixture").withProperty("KNOWLEDGE_CHAT_CONFIGURATION","fixture")
            .withProperty("app.dependencies.qdrant.base-url",System.getenv("PHASE4_QDRANT_URL")).withProperty("KNOWLEDGE_HYBRID_COLLECTION","p4_"+UUID.randomUUID().toString().replace("-",""))
            .withProperty("app.knowledge.processing-configurations","embedding:fixture,rerank:fixture,chat:fixture");
        directory=Files.createTempDirectory(Path.of(System.getenv("PHASE4_LOCAL")),"lucene-");bm25=new LuceneKnowledgeIndex(directory);vector=new QdrantGenerationIndex(env);embedding=new HttpKnowledgeEmbedding(env);reranker=new HttpKnowledgeReranker(env);
        var policy=new KnowledgePurposePolicy(env);reader=new KnowledgeGenerationReader(db,json,tm,policy);pipeline=new KnowledgeIndexPipeline(db,json,tm,policy,List.of(embedding),List.of(vector),List.of(bm25));hybrid=new HybridKnowledgeRetrieval(reader,bm25,vector,embedding,reranker,env,db);
    }
    void reply(HttpExchange exchange,int code,Object response) throws java.io.IOException {byte[] bytes=json.writeValueAsBytes(response);exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
    @AfterEach void close(){supplier.stop(0);try{bm25.close();}catch(RuntimeException ignored){}}
    record Fixture(long id,String key,int revision,String job) {}
    Fixture source(String model,String content){String key="p4-"+UUID.randomUUID();db.update("""
        INSERT INTO knowledge_documents(document_key,version,title,source_type,self_authored,is_simulated,model_scope,models_json,checked_on,use_condition,
            distribution_allowed,external_upload_allowed,local_file,provenance_json,layout_version)
        VALUES(?,'v1','开发合成资料','enterprise_faq',TRUE,TRUE,'MODELS',?,'2026-11-01','仅开发夹具',FALSE,TRUE,'fixture','{}','knowledge-layout-v2')
        """,key,json.writeValueAsString(List.of(model)));long id=db.queryForObject("SELECT id FROM knowledge_documents WHERE document_key=?",Long.class,key);return revision(new Fixture(id,key,0,null),content,model);}
    Fixture revision(Fixture old,String content,String model){int revision=old.revision+1;String hash=KnowledgeIndexPipeline.digest(old.id,revision);String job=UUID.randomUUID().toString();
        var metadata=Map.of("version","v"+revision,"title","开发合成资料","sourceType","enterprise_faq","isSimulated",true,"modelScope","MODELS","models",List.of(model),"useCondition","仅开发夹具","distributionAllowed",false,"source",Map.of("checkedOn","2026-11-01","description","synthetic development fixture"),
            "processingPermissions",Map.of("embedding",Map.of("allowed",true,"configurationId","fixture"),"rerank",Map.of("allowed",true,"configurationId","fixture"),"chat",Map.of("allowed",true,"configurationId","fixture")));
        db.update("INSERT INTO knowledge_revisions(document_id,revision,storage_key,file_name,metadata_json,content_hash,layout_hash,config_hash,binding_hash,layout_version,extracted_text,chunk_count,created_by) VALUES(?,?,?,'fixture.md',?,?,?,?,?,'knowledge-layout-v2',?,1,?)",old.id,revision,"fixture-"+hash,json.writeValueAsString(metadata),hash,hash,hash,hash,content,actor);
        db.update("INSERT INTO knowledge_chunks(document_id,revision,chunk_index,stable_chunk_id,content,section,layout_version) VALUES(?,?,0,?,?,'条件与步骤','knowledge-layout-v2')",old.id,revision,KnowledgeIndexPipeline.digest(old.id,revision,content),content);
        for(String purpose:List.of("embedding","rerank","chat"))db.update("INSERT INTO knowledge_permissions(document_id,revision,purpose,allowed,configuration_id) VALUES(?,?,?,TRUE,'fixture')",old.id,revision,purpose);
        db.update("INSERT INTO knowledge_publication_requests(id,document_id,revision,binding_hash,actor_id) VALUES(?,?,?,?,?)",job,old.id,revision,hash,actor);db.update("UPDATE knowledge_documents SET revision=? WHERE id=?",revision,old.id);
        return new Fixture(old.id,old.key,revision,job);
    }
    void publish(Fixture f){pipeline.process(f.job,f.id,f.revision,()->{});}
    KnowledgeRetrievalService.Result search(String query){return hybrid.search("P-100",query,ids->{throw new AssertionError("no legacy data in this fixture");});}
    @Test void realIndexesPublishFilterCrossModelAndTraceAllThreeConfigurations(){
        var a=source("P-100","警告：先关闭电源，再处理E-101进纸卡纸。");var b=source("P-200","E-101 E-101 E-101：其他型号步骤，严禁引用到P-100。");publish(a);publish(b);
        for(String config:List.of(HybridKnowledgeRetrieval.VECTOR,HybridKnowledgeRetrieval.RRF,HybridKnowledgeRetrieval.RERANK)){
            env.withProperty("KNOWLEDGE_RETRIEVAL_CONFIGURATION",config);
            for(String query:List.of("E-101卡纸怎么处理","打印时纸张老是卡在里面怎么办","这个设备的开发说明是什么")){
                var result=search(query);assertEquals("HIT",result.status());assertFalse(result.sources().isEmpty());assertTrue(result.sources().stream().allMatch(s->s.documentId()==a.id));assertEquals(config,result.trace().get("configuration"));
            }
        }
        assertEquals(3,reranks.get());assertEquals("MODEL_CLARIFICATION_REQUIRED",search("P-200的E-101怎么办").status());
    }
    @Test void replacementDisableDuplicateAndDiskReopenPreserveOldCitation(){
        var a=source("P-100","旧版：先断电再清除卡纸E-101。");publish(a);var before=search("E-101").sources().get(0);int count=embeddings.get();long epoch=pipeline.epoch();publish(a);assertEquals(count,embeddings.get());assertEquals(epoch,pipeline.epoch());
        var next=revision(a,"新版：先断电且等待冷却后再清理E-101。","P-100");publish(next);assertEquals("v2",search("E-101").sources().get(0).version());assertEquals("v1",before.version());assertTrue(before.content().contains("旧版"));
        bm25.close();bm25=new LuceneKnowledgeIndex(directory);var entry=reader.snapshot("P-100","fixture","fixture","fixture").entries().values().iterator().next();bm25.verify(entry.generation());vector.verify(entry.generation());
        pipeline.disable(next.id,next.revision);assertEquals("NO_EVIDENCE",search("E-101").status());assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM knowledge_chunks WHERE document_id=?",Integer.class,a.id));
    }
    @Test void rerankPermissionAndChatRevocationPreventDisclosure(){
        var a=source("P-100","警告：先断电再处理E-101卡纸。");publish(a);db.update("UPDATE knowledge_permissions SET allowed=FALSE WHERE document_id=? AND purpose='rerank'",a.id);
        assertEquals("EXCERPT_ONLY",search("E-101").status());assertEquals(0,reranks.get());
        db.update("UPDATE knowledge_permissions SET allowed=FALSE WHERE document_id=? AND purpose='chat'",a.id);assertEquals("EVIDENCE_NOT_AUTHORIZED",search("E-101").status());assertEquals(0,reranks.get());
    }
    @Test void epochChangeRetriesOnceAndRepeatedChangeNeverReturnsStaleSources(){
        publish(source("P-100","警告：先断电再清理E-101卡纸。"));var once=new AtomicInteger();onRerank=()->{if(once.getAndIncrement()==0)db.update("UPDATE knowledge_epoch SET value=value+1 WHERE id=1");};
        var result=search("E-101");assertEquals("HIT",result.status());assertEquals(2,result.trace().get("snapshotAttempts"));assertEquals(2,reranks.get());
        onRerank=()->db.update("UPDATE knowledge_epoch SET value=value+1 WHERE id=1");var failed=search("E-101");assertEquals("RETRIEVAL_UNAVAILABLE",failed.status());assertEquals("KNOWLEDGE_CHANGED_TWICE",failed.reason());assertTrue(failed.sources().isEmpty());
    }
    @Test void disablingDuringQueryAndPermissionRaceCannotLeakOldResult(){
        var a=source("P-100","警告：先断电再清理E-101卡纸。");publish(a);onRerank=()->db.update("UPDATE knowledge_permissions SET allowed=FALSE WHERE document_id=? AND purpose='chat'",a.id);
        assertEquals("EVIDENCE_NOT_AUTHORIZED",search("E-101").status());assertEquals(1,reranks.get());
    }
    @Test void rerankOutageAndRealLuceneClosureHaveIndependentDegradedStates(){
        publish(source("P-100","警告：先断电再清理E-101卡纸。"));rerankFailure=true;var excerpts=search("E-101");assertEquals("EXCERPT_ONLY",excerpts.status());assertEquals("UNAVAILABLE",excerpts.trace().get("rerank"));assertFalse(excerpts.sources().isEmpty());
        rerankFailure=false;bm25.close();var degraded=search("E-101");assertEquals("DEGRADED",degraded.status());assertEquals("UNAVAILABLE",degraded.trace().get("bm25"));assertEquals("OK",degraded.trace().get("rerank"));
        supplier.stop(0);var failed=search("E-101");assertEquals("RETRIEVAL_UNAVAILABLE",failed.status());assertTrue(failed.sources().isEmpty());
    }
    @Test void actualQdrantStopKeepsBm25ThenRecoversAndSavedEmbeddingIsReused() throws Exception {
        var a=source("P-100","警告：先断电再清理E-101卡纸。");publish(a);String container=System.getenv("PHASE4_QDRANT_CONTAINER");assertTrue(container.matches("printer-p4-qdrant-[a-f0-9]+"));
        var inspect=new ProcessBuilder("docker","inspect",container).start();var actual=json.readTree(inspect.getInputStream().readAllBytes()).get(0);assertEquals("product-phase4-qdrant",actual.path("Config").path("Labels").path("local.validation").asString());assertEquals(0,inspect.waitFor());
        docker("stop",container);
        try{
            var degraded=search("E-101");assertEquals("DEGRADED",degraded.status());assertEquals("UNAVAILABLE",degraded.trace().get("vector"));
            var next=revision(a,"新版：警告，先断电再处理E-101卡纸。","P-100");assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->publish(next));int saved=embeddings.get();
            docker("start",container);awaitQdrant();publish(next);assertEquals(saved,embeddings.get());assertEquals("v2",search("E-101").sources().get(0).version());
        }finally{docker("start",container);}
    }
    @Test void ruleTopicDirectUsesManagedGenerationWithoutQueryEmbeddingOrRerank(){
        var a=source("P-100","合成规则：填写故障描述、现场信息、联系人和联系方式。");
        var topic=RuleTopicCatalog.Topic.REQUIRED_FIELDS;
        db.update("UPDATE knowledge_documents SET document_key=? WHERE id=?",topic.documentKey(),a.id);
        db.update("UPDATE knowledge_chunks SET section=? WHERE document_id=?",topic.section(),a.id);publish(a);int calls=embeddings.get();
        var result=hybrid.rule("P-100",topic);assertEquals("HIT",result.status());assertNull(result.sources().get(0).score());assertEquals(calls,embeddings.get());assertEquals(0,reranks.get());
    }
    @Test void forgedCandidatePayloadIsRejectedBeforeRerankAndManifestTamperingFailsClosed(){
        var a=source("P-100","警告：先断电再清理E-101卡纸。");publish(a);var entry=reader.snapshot("P-100","fixture","fixture","fixture").entries().values().iterator().next();
        var g=entry.generation();String point=QdrantGenerationIndex.point(g.id(),g.chunks().get(0).id());
        new IndexHttp().send("POST",URI.create(System.getenv("PHASE4_QDRANT_URL")+"/collections/"+env.getProperty("KNOWLEDGE_HYBRID_COLLECTION")+"/points/payload?wait=true"),null,
            Map.of("points",List.of(point),"payload",Map.of("config","forged")),5000,"LOCAL_TEST_INJECTION",false,false);
        var result=search("E-101");assertEquals("DEGRADED",result.status());assertEquals("BINDING_REJECTED",result.trace().get("vector"));assertEquals(1,result.sources().size());
        db.update("UPDATE knowledge_chunks SET content='篡改删除了断电条件' WHERE document_id=?",a.id);
        assertEquals("KNOWLEDGE_RETRIEVAL_BINDING_MISMATCH",assertThrows(KnowledgeFailure.class,()->search("E-101")).getMessage());
    }
    static void docker(String operation,String name) throws Exception {var p=new ProcessBuilder("docker",operation,name).start();p.getInputStream().readAllBytes();p.getErrorStream().readAllBytes();assertEquals(0,p.waitFor());}
    void awaitQdrant() throws Exception {for(int i=0;i<60;i++){try{var connection=new URL(System.getenv("PHASE4_QDRANT_URL")+"/healthz").openConnection();connection.setConnectTimeout(500);connection.setReadTimeout(500);connection.getInputStream().close();return;}catch(Exception unavailable){Thread.sleep(250);}}fail("Qdrant did not recover");}
}

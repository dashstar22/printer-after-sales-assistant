package local.printer.knowledge;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import local.printer.order.OrderService;
import local.printer.seed.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL + Qdrant, explicitly constant local publication embeddings. No semantic-quality claim. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleTopicIT {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String HASH="78613ca55b980cbd9b6989ac1e10282abb1810827a8b1ca623be0835e0bbf75e";
    static final String COLLECTION="layered_rules_"+UUID.randomUUID().toString().replace("-","");
    static final String QDRANT=System.getenv().getOrDefault("PHASE4_QDRANT_URL","http://127.0.0.1:16333");
    static final AtomicInteger embeddings=new AtomicInteger();static HttpServer embedding;
    @DynamicPropertySource static void config(DynamicPropertyRegistry r)throws Exception{
        embedding=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        embedding.createContext("/v1/embeddings",exchange->{
            embeddings.incrementAndGet();var input=JSON.readTree(exchange.getRequestBody().readAllBytes()).path("input");
            var vector=new ArrayList<Double>(Collections.nCopies(1024,0.0));vector.set(0,1.0);var data=new ArrayList<Object>();
            for(int i=0;i<(input.isArray()?input.size():1);i++)data.add(Map.of("index",i,"embedding",vector));
            byte[] body=JSON.writeValueAsBytes(Map.of("data",data));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });embedding.start();
        r.add("KNOWLEDGE_EMBEDDING_API_KEY",()->"local-fixture");r.add("KNOWLEDGE_EMBEDDING_BASE_URL",()->"http://127.0.0.1:"+embedding.getAddress().getPort()+"/v1");
        r.add("KNOWLEDGE_VECTOR_COLLECTION",()->COLLECTION);r.add("app.dependencies.qdrant.base-url",()->QDRANT);
        r.add("KNOWLEDGE_CHAT_ALLOWED_HASHES",()->HASH);r.add("KNOWLEDGE_RETRIEVAL_MIN_SCORE",()->"0.6");
    }
    @AfterAll void stop(){embedding.stop(0);}
    @Autowired JdbcTemplate jdbc;@Autowired KnowledgeMapper mapper;@Autowired KnowledgeProcessingMapper processing;
    @Autowired PlatformTransactionManager tm;@Autowired Environment env;@Autowired DataSource ds;
    @Autowired OrderService orders;@Autowired DemoSeedService seed;@Autowired KnowledgeRetrievalService service;
    long customer,order,foreignOrder,document;
    @BeforeAll void publish(){
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_rule_topic_it_"));
        seed.seed(new SeedPasswords(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()));
        customer=jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_a'",Long.class);
        order=jdbc.queryForObject("SELECT id FROM orders WHERE customer_id=?",Long.class,customer);
        foreignOrder=jdbc.queryForObject("SELECT o.id FROM orders o JOIN users u ON u.id=o.customer_id WHERE u.username='demo_customer_b'",Long.class);
        Path root=Path.of(System.getenv("LAYERED_REPO_ROOT"));
        new KnowledgeRegistrationService(new KnowledgeApplicationService(mapper,tm)).register(root,"docs/sources/business-supplement-reviewed-v1.json",List.of("business-supplement-draft-fields"));
        document=jdbc.queryForObject("SELECT id FROM knowledge_documents",Long.class);
        new KnowledgeExtractionService(new KnowledgeApplicationService(mapper,tm)).extract(root,document,800,100);
        new KnowledgePublicationService(mapper,processing,ds,tm,env).publish(root,document,800,100);
        assertEquals(HASH,mapper.documentById(document).contentHash());assertTrue(embeddings.get()>0);
        System.out.println("LAYERED_RULE_FIXTURE collection="+COLLECTION+" providerCalls=0");
    }
    KnowledgeRetrievalService.Result rule(RuleTopicCatalog.Topic topic){return service.ruleOwnedForAssistant(customer,order,topic);}
    @Test void allFourTopicsReturnExactPublishedChapterAndNullScoreWithoutEmbedding(){
        int before=embeddings.get();
        for(var topic:RuleTopicCatalog.Topic.values()){
            var result=rule(topic);assertEquals("HIT",result.status());assertEquals("RULE_TOPIC_MATCH",result.reason());assertEquals(0.6,result.minScore());
            assertFalse(result.sources().isEmpty());
            for(var source:result.sources()){
                assertNull(source.score());assertEquals(topic.section(),source.section());
                assertEquals(mapper.chunks(document).stream().filter(c->c.id()==source.chunkId()).findFirst().orElseThrow().content(),source.content());
            }
        }
        assertEquals(before,embeddings.get());
    }
    @Test void inactiveVersionIsNeverUsed(){
        jdbc.update("UPDATE knowledge_documents SET active=false WHERE id=?",document);
        try{assertEquals("NO_EVIDENCE",rule(RuleTopicCatalog.Topic.REQUIRED_FIELDS).status());}
        finally{jdbc.update("UPDATE knowledge_documents SET active=true WHERE id=?",document);}
    }
    @Test void changedVersionOrLayoutCannotReuseOldPublishedPoint(){
        var d=mapper.documentById(document);
        jdbc.update("UPDATE knowledge_documents SET version=? WHERE id=?",d.version()+"-tampered",document);
        try{assertEquals("NO_EVIDENCE",rule(RuleTopicCatalog.Topic.REQUIRED_FIELDS).status());}
        finally{jdbc.update("UPDATE knowledge_documents SET version=? WHERE id=?",d.version(),document);}
        var c=mapper.chunks(document).stream().filter(chunk->RuleTopicCatalog.Topic.REQUIRED_FIELDS.section().equals(chunk.section())).findFirst().orElseThrow();
        jdbc.update("UPDATE knowledge_chunks SET content=? WHERE id=?",c.content()+"tampered",c.id());
        try{assertEquals("NO_EVIDENCE",rule(RuleTopicCatalog.Topic.REQUIRED_FIELDS).status());}
        finally{jdbc.update("UPDATE knowledge_chunks SET content=? WHERE id=?",c.content(),c.id());}
    }
    MockEnvironment environment(String allowed,String qdrant){return new MockEnvironment().withProperty("KNOWLEDGE_VECTOR_COLLECTION",COLLECTION)
        .withProperty("KNOWLEDGE_EMBEDDING_API_KEY","local-fixture").withProperty("KNOWLEDGE_EMBEDDING_BASE_URL","http://127.0.0.1:9/v1")
        .withProperty("app.dependencies.qdrant.base-url",qdrant).withProperty("KNOWLEDGE_CHAT_ALLOWED_HASHES",allowed).withProperty("KNOWLEDGE_RETRIEVAL_MIN_SCORE","0.6");}
    @Test void separateChatPermissionIsRequired(){
        var denied=new KnowledgeRetrievalService(mapper,processing,orders,environment("",QDRANT),ds,tm);
        var result=denied.ruleOwnedForAssistant(customer,order,RuleTopicCatalog.Topic.REQUIRED_FIELDS);
        assertEquals("EVIDENCE_NOT_AUTHORIZED",result.status());assertTrue(result.sources().isEmpty());
        jdbc.update("UPDATE knowledge_documents SET external_upload_allowed=false WHERE id=?",document);
        try{assertEquals("EVIDENCE_NOT_AUTHORIZED",rule(RuleTopicCatalog.Topic.REQUIRED_FIELDS).status());}
        finally{jdbc.update("UPDATE knowledge_documents SET external_upload_allowed=true WHERE id=?",document);}
    }
    @Test void modelApplicabilityAndPublicationBindingStillApply(){
        jdbc.update("UPDATE knowledge_documents SET model_scope='MODELS',models_json='[\"FOREIGN-MODEL\"]' WHERE id=?",document);
        try{assertEquals("NO_EVIDENCE",rule(RuleTopicCatalog.Topic.REQUIRED_FIELDS).status());}
        finally{jdbc.update("UPDATE knowledge_documents SET model_scope='GENERAL',models_json='[]' WHERE id=?",document);}
        var binding=processing.processing(document);
        jdbc.update("UPDATE knowledge_processing SET model='wrong-model' WHERE document_id=?",document);
        try{assertEquals("KNOWLEDGE_RETRIEVAL_BINDING_MISMATCH",assertThrows(KnowledgeFailure.class,()->rule(RuleTopicCatalog.Topic.REQUIRED_FIELDS)).getMessage());}
        finally{jdbc.update("UPDATE knowledge_processing SET model=? WHERE document_id=?",binding.model(),document);}
    }
    @Test void ownershipAndUnavailableDependenciesNeverBecomeSuccess(){
        assertThrows(OrderService.NotFound.class,()->service.ruleOwnedForAssistant(customer,foreignOrder,RuleTopicCatalog.Topic.REQUIRED_FIELDS));
        var unavailable=new KnowledgeRetrievalService(mapper,processing,orders,environment(HASH,"http://127.0.0.1:9"),ds,tm);
        assertEquals("KNOWLEDGE_VECTOR_UNAVAILABLE",assertThrows(KnowledgeFailure.class,()->unavailable.ruleOwnedForAssistant(customer,order,RuleTopicCatalog.Topic.REQUIRED_FIELDS)).getMessage());
    }
    JsonNode qdrant(String method,String suffix,Object body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(QDRANT+"/collections/"+COLLECTION+suffix)).header("Content-Type","application/json")
            .method(method,HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());return JSON.readTree(response.body());
    }
    @Test void actualQdrantPayloadTamperingIsRejected()throws Exception{
        var source=rule(RuleTopicCatalog.Topic.REQUIRED_FIELDS).sources().get(0);
        var point=qdrant("POST","/points",Map.of("ids",List.of(source.pointId()),"with_payload",true,"with_vector",true)).path("result").get(0);
        var original=JSON.readValue(point.path("payload").toString(),Map.class);var tampered=new LinkedHashMap<String,Object>(original);tampered.put("version","forged");
        qdrant("PUT","/points/payload?wait=true",Map.of("points",List.of(source.pointId()),"payload",tampered));
        try{assertEquals("KNOWLEDGE_VECTOR_VERIFY_FAILED",assertThrows(KnowledgeFailure.class,()->rule(RuleTopicCatalog.Topic.REQUIRED_FIELDS)).getMessage());}
        finally{qdrant("PUT","/points/payload?wait=true",Map.of("points",List.of(source.pointId()),"payload",original));}
        assertEquals("HIT",rule(RuleTopicCatalog.Topic.REQUIRED_FIELDS).status());
    }
}

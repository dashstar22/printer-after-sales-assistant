package local.printer.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import local.printer.knowledge.index.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** Reads the actual HTTP-published database/indexes only after that owned app has stopped. */
class HybridPublishedReadbackIT {
    @Test void readPublishedVersionsAndPreserveHistoricalSourceSnapshot() throws Exception {
        String url=System.getenv("PHASE4_DB_URL");assertNotNull(url);assertTrue(url.contains("printer_p4_http_"));
        var ds=new DriverManagerDataSource(url,System.getenv("PHASE4_DB_USER"),System.getenv("PHASE4_DB_PASSWORD"));var db=new JdbcTemplate(ds);var tm=new JdbcTransactionManager(ds);var json=JsonMapper.builder().build();
        var env=new MockEnvironment();for(String key:List.of("KNOWLEDGE_EMBEDDING_BASE_URL","KNOWLEDGE_EMBEDDING_DIMENSIONS","KNOWLEDGE_RERANK_URL","KNOWLEDGE_HYBRID_COLLECTION","KNOWLEDGE_EMBEDDING_CONFIGURATION","KNOWLEDGE_RERANK_CONFIGURATION","KNOWLEDGE_CHAT_CONFIGURATION"))env.withProperty(key,Objects.requireNonNull(System.getenv(key),key));
        env.withProperty("app.dependencies.qdrant.base-url",System.getenv("PHASE4_QDRANT_URL")).withProperty("app.knowledge.processing-configurations","embedding:p4,rerank:p4,chat:p4");
        var reader=new KnowledgeGenerationReader(db,json,tm,new KnowledgePurposePolicy(env));var fixture=json.readTree(Files.readString(Path.of(System.getenv("PHASE4_FIXTURES"))));
        var output=Path.of(System.getenv("PHASE4_READBACK"));boolean after="after".equals(System.getenv("PHASE4_READBACK_STAGE"));var results=new ArrayList<Object>();
        try(var bm25=new LuceneKnowledgeIndex(Path.of(System.getenv("KNOWLEDGE_LUCENE_ROOT")))){
            var hybrid=new HybridKnowledgeRetrieval(reader,bm25,new QdrantGenerationIndex(env),new HttpKnowledgeEmbedding(env),new HttpKnowledgeReranker(env),env,db);
            var configs=after?List.of(HybridKnowledgeRetrieval.VECTOR,HybridKnowledgeRetrieval.RRF,HybridKnowledgeRetrieval.RERANK):List.of(HybridKnowledgeRetrieval.RERANK);
            for(int i=0;i<configs.size();i++){
                env.withProperty("KNOWLEDGE_RETRIEVAL_CONFIGURATION",configs.get(i));String query=fixture.path("queries").get(0).asString();
                var result=hybrid.search(fixture.path("model").asString(),query,ids->{throw new AssertionError("No legacy knowledge in HTTP fixture");});
                assertEquals("HIT",result.status(),result.reason());assertFalse(result.sources().isEmpty());assertTrue(result.sources().stream().allMatch(s->(after?"v2":"v1").equals(s.version())));results.add(result);
            }
            if(after){
                var old=json.readTree(Files.readString(output.resolveSibling("before.json")));
                for(var source:old.path("results").get(0).path("sources"))assertEquals(source.path("content").asString(),db.queryForObject("SELECT content FROM knowledge_chunks WHERE id=?",String.class,source.path("chunkId").asLong()));
                assertEquals("v1",old.path("results").get(0).path("sources").get(0).path("version").asString());
            }
            Files.writeString(output,json.writeValueAsString(Map.of("status","PASS","stage",after?"after":"before","results",results)));
        }
    }
}

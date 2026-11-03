package local.printer.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.printer.knowledge.index.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class KnowledgeHttpProtocolTest {
    @Test void containerEndpointsRequireExactConfiguredInternalHost(){
        var env=new MockEnvironment().withProperty("KNOWLEDGE_INTERNAL_HOSTS","qdrant");
        assertEquals("http://qdrant:6333",IndexHttp.internalEndpoint("http://qdrant:6333",env).toString());
        for(String url:List.of("http://qdrant.evil:6333","http://other:6333","http://user@qdrant:6333","http://qdrant:6333?x=1","http://qdrant:6333#fragment"))assertThrows(IllegalArgumentException.class,()->IndexHttp.internalEndpoint(url,env));
    }
    HttpServer server;String base;volatile String body;volatile int code=200,delay=0;AtomicInteger calls=new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();try{Thread.sleep(delay);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception ignored){}finally{exchange.close();}});server.start();base="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop(){server.stop(0);}
    MockEnvironment env(){return new MockEnvironment().withProperty("KNOWLEDGE_RERANK_URL",base+"/rerank").withProperty("KNOWLEDGE_EMBEDDING_BASE_URL",base).withProperty("KNOWLEDGE_EMBEDDING_DIMENSIONS","2");}
    @Test void rerankOutOfOrderMapsByIndexAndKeepsUnmeasuredScores(){
        body="{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.8},{\"index\":0,\"relevance_score\":0.2}]}}";
        assertEquals(List.of(0.2,0.8),new HttpKnowledgeReranker(env()).score("卡纸",List.of("先断电","再处理")));assertEquals(1,calls.get());
    }
    @Test void identicalBodiesKeepTheirIndependentInputPositions(){
        body="{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.8},{\"index\":0,\"relevance_score\":0.2}]}}";
        assertEquals(List.of(0.2,0.8),new HttpKnowledgeReranker(env()).score("卡纸",List.of("先断电，再清除卡纸","先断电，再清除卡纸")));
        assertEquals(1,calls.get());
    }
    @Test void rerankMissingDuplicateWrongIndexAndNonFiniteAreRejected(){
        var client=new HttpKnowledgeReranker(env());
        for(String rows:List.of("[{\"index\":0,\"relevance_score\":0.2}]","[{\"index\":0,\"relevance_score\":0.2},{\"index\":0,\"relevance_score\":0.8}]","[{\"index\":0,\"relevance_score\":0.2},{\"index\":2,\"relevance_score\":0.8}]","[{\"index\":0,\"relevance_score\":0.2},{\"index\":1,\"relevance_score\":4}]")){
            body="{\"output\":{\"results\":"+rows+"}}";assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->client.score("卡纸",List.of("先断电","再处理")));}
    }
    @Test void timeoutDoesNotAutomaticallyRepeatSupplierRequest(){
        body="{}";delay=250;var client=new HttpKnowledgeReranker(env().withProperty("KNOWLEDGE_RERANK_TIMEOUT_MS","20"));
        assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->client.score("卡纸",List.of("先断电")));assertTrue(calls.get()<=1);
    }
    @Test void supplierRedirectIsNotFollowedAndUnknownEmbeddingCannotBeTreatedAsFree(){
        body="{}";code=302;var embedding=new HttpKnowledgeEmbedding(env());var failure=assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->embedding.embed(List.of("合成测试")));assertTrue(failure.unknownCost());assertEquals(1,calls.get());
    }
    @Test void embeddingRejectsMissingMappingAndMapsReversedResponse(){
        body="{\"data\":[{\"index\":1,\"embedding\":[1,2]},{\"index\":0,\"embedding\":[2,3]}]}";var client=new HttpKnowledgeEmbedding(env());assertEquals(List.of(List.of(2.0,3.0),List.of(1.0,2.0)),client.embed(List.of("a","b")));
        body="{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}";assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->client.embed(List.of("a")));
    }
    @Test void externalCallsFailClosedAndInputsAreBoundedBeforeTransport(){
        var embedding=new HttpKnowledgeEmbedding(env().withProperty("KNOWLEDGE_EMBEDDING_BASE_URL","https://example.com/v1"));assertEquals("EXTERNAL_CALL_NOT_AUTHORIZED",assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->embedding.embed(List.of("a"))).code());
        var rerank=new HttpKnowledgeReranker(env());assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->rerank.score("query",Collections.nCopies(21,"text")));assertEquals(0,calls.get());
    }
}

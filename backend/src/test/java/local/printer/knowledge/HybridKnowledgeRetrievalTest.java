package local.printer.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import local.printer.knowledge.index.*;
import local.printer.knowledge.index.KnowledgeIndexAdapters.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class HybridKnowledgeRetrievalTest {
    @Test void successfulBoundedRerankKeepsInputLimitedTraceWithoutDiscardingUsableEvidence(){
        var reader=mock(KnowledgeGenerationReader.class);var bm25=mock(LuceneKnowledgeIndex.class);var vector=mock(QdrantGenerationIndex.class);
        var embedding=mock(HttpKnowledgeEmbedding.class);var reranker=mock(HttpKnowledgeReranker.class);var db=mock(JdbcTemplate.class);
        var env=new MockEnvironment().withProperty("KNOWLEDGE_CHAT_CONFIGURATION","fixture");
        when(embedding.configurationId()).thenReturn("fixture");when(embedding.identity()).thenReturn("embedding");when(embedding.dimensions()).thenReturn(2);
        when(reranker.configurationId()).thenReturn("fixture");when(vector.identity()).thenReturn("vector");when(bm25.identity()).thenReturn("bm25");
        when(embedding.embed(anyList())).thenReturn(List.of(List.of(1.0,0.1)));
        var chunks=new ArrayList<Chunk>();for(int i=0;i<20;i++)chunks.add(new Chunk("chunk-"+i,i,"合成步骤".repeat(100)+i,1,"条件"));
        String config=KnowledgeIndexPipeline.digest("revision","fixture","embedding",2,"vector","bm25");
        var generation=new Generation("generation",1,1,"layout",config,KnowledgeIndexPipeline.manifest(chunks),"test",Map.of(),chunks);
        var entry=new KnowledgeGenerationReader.Entry(generation,null,Map.of(),Map.of("embedding",true,"rerank",true,"chat",true),"revision","fixture");
        when(reader.snapshot(anyString(),anyString(),anyString(),anyString())).thenReturn(new KnowledgeGenerationReader.Snapshot(1,"P-100",Map.of(generation.id(),entry),"same"));
        var hits=chunks.stream().map(c->new IndexQuery.Hit(generation.id(),c.id(),0.9,config,generation.manifestHash(),KnowledgeIndexPipeline.digest(c.text()))).toList();
        when(vector.search(any(),anyList())).thenReturn(hits);when(bm25.search(any(),anyString())).thenReturn(hits);
        when(reader.source(eq(entry),anyString(),anyDouble())).thenAnswer(call->{
            var c=chunks.stream().filter(chunk->chunk.id().equals(call.getArgument(1))).findFirst().orElseThrow();
            return new KnowledgeRetrievalService.Source(c.id(),1,c.index()+1,c.index(),0.9,c.text(),"合成资料","test","v1","MODELS",List.of("P-100"),1,"条件","enterprise_faq","合成来源",null,null,null,"仅验证",true,true);
        });
        when(reranker.score(anyString(),anyList())).thenAnswer(call->Collections.nCopies(((List<?>)call.getArgument(1)).size(),0.9));
        var result=new HybridKnowledgeRetrieval(reader,bm25,vector,embedding,reranker,env,db).search("P-100","E-101卡纸",ids->{throw new AssertionError("no legacy sources");});
        assertEquals("HIT",result.status());assertFalse(result.sources().isEmpty());assertEquals("PERMISSION_OR_INPUT_LIMITED",result.trace().get("rerank"));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> sent=ArgumentCaptor.forClass(List.class);
        verify(reranker).score(eq("E-101卡纸"),sent.capture());assertTrue(sent.getValue().size()<20);assertFalse(sent.getValue().isEmpty());
        assertTrue(HybridRanking.tokenUpperBound("E-101卡纸")+sent.getValue().stream().mapToInt(HybridRanking::tokenUpperBound).sum()<=20000);
    }
}

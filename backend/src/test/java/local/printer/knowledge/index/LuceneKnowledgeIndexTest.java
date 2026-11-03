package local.printer.knowledge.index;

import static org.junit.jupiter.api.Assertions.*;
import static local.printer.knowledge.index.KnowledgeIndexAdapters.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LuceneKnowledgeIndexTest {
    @TempDir Path root;
    static Generation generation(String id,String model,List<String> texts){
        var chunks=new ArrayList<Chunk>();for(int i=0;i<texts.size();i++)chunks.add(new Chunk(KnowledgeIndexPipeline.digest(id,i),i,texts.get(i),1,"故障说明"));
        return new Generation(KnowledgeIndexPipeline.digest(id),1,1,"layout","config",KnowledgeIndexPipeline.manifest(chunks),"manual",Map.of("modelScope","MODELS","models",List.of(model)),chunks);
    }
    @Test void realChineseCodesAndReopenKeepExactGenerationIdentity(){
        var a=generation("a","P-100",List.of("警告：先断电，确认温度降低后处理卡纸。E-101 表示进纸异常。","安装驱动后再连接打印机。"));
        var b=generation("b","P-200",List.of("E-101 E-101 E-101：其他型号专用清理步骤。"));
        try(var index=new LuceneKnowledgeIndex(root)){
            index.write(a);index.write(b);KnowledgeIndexPipeline.verify(a,index.verify(a));
            assertEquals(a.chunks().get(0).id(),index.search(new IndexQuery.Scope("P-100",Set.of(a.id(),b.id())),"Ｅ－１０１").get(0).chunkId());
            assertTrue(index.search(new IndexQuery.Scope("P-100",Set.of(a.id())),"卡纸").stream().allMatch(h->h.generation().equals(a.id())));
            assertTrue(index.search(new IndexQuery.Scope("P-100",Set.of(b.id())),"E-101").isEmpty());
            assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->new LuceneKnowledgeIndex(root));
            index.write(a);assertEquals(2,index.verify(a).chunkIds().size());
        }
        try(var reopened=new LuceneKnowledgeIndex(root)){KnowledgeIndexPipeline.verify(a,reopened.verify(a));assertFalse(reopened.search(new IndexQuery.Scope("P-100",Set.of(a.id())),"卡纸").isEmpty());}
    }
    @Test void tamperedBodyAndManifestCannotPassVerification(){
        var a=generation("a","P-100",List.of("先关机，再清除卡纸。"));
        try(var index=new LuceneKnowledgeIndex(root)){index.write(a);var different=new Generation(a.id(),1,1,a.layoutHash(),a.configurationHash(),a.manifestHash(),a.documentKey(),a.metadata(),List.of(new Chunk(a.chunks().get(0).id(),0,"删除了断电条件",1,"故障说明")));
            assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->index.verify(different));}
    }
    @Test void generationAndReviewedCodeBoundariesAreExact(){
        assertEquals(Set.of("E-101","P-100"),IndexQuery.codes("故障Ｅ－１０１，型号P-100"));
    }
    @Test void modelApplicabilityDoesNotBecomeLexicalEvidenceForAnUnrelatedPage(){
        var manual=generation("model-noise","P-100",List.of(
            "P-100 P-100 P-100 specifications and model cover",
            "E-101 Paper Jam: turn off the machine before removing paper.",
            "Paper Size: choose the correct paper setting."));
        try(var index=new LuceneKnowledgeIndex(root)){
            index.write(manual);var scope=new IndexQuery.Scope("P-100",Set.of(manual.id()));
            var hits=index.search(scope,"P-100 出现 E-101，应该如何处理？");
            assertEquals(manual.chunks().get(1).id(),hits.get(0).chunkId());
            assertFalse(hits.stream().anyMatch(h->h.chunkId().equals(manual.chunks().get(0).id())));
            assertTrue(index.search(scope,"Ｐ－１００").isEmpty(),"model-only input contains no evidence query");
        }
    }
    @Test void removingTheScopedModelPreservesIndependentCodesAndLongerModelTokens(){
        assertEquals("  E-101",IndexQuery.lexicalText("P-100","Ｐ－１００  E-101"));
        assertEquals("P-1000 E-101",IndexQuery.lexicalText("P-100","P-1000 E-101"));
        assertEquals("P-100-XL E-101",IndexQuery.lexicalText("P-100","P-100-XL E-101"));
    }
}

package local.printer.knowledge.index;

import static org.junit.jupiter.api.Assertions.*;
import static local.printer.knowledge.index.KnowledgeIndexAdapters.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class KnowledgeIndexPipelineTest {
    @Test void identityBindsOrderTextLocationsAndConfiguration() {
        var a=new Chunk("a".repeat(64),0,"警告：断电后检查 E-1100",1,"维护");
        var b=new Chunk("b".repeat(64),1,"检查220V电源",2,"维护");
        assertEquals(KnowledgeIndexPipeline.manifest(List.of(a,b)),KnowledgeIndexPipeline.manifest(List.of(a,b)));
        assertNotEquals(KnowledgeIndexPipeline.manifest(List.of(a,b)),KnowledgeIndexPipeline.manifest(List.of(b,a)));
        assertNotEquals(KnowledgeIndexPipeline.digest(1,1,"layout","configA"),KnowledgeIndexPipeline.digest(1,1,"layout","configB"));
        assertNotEquals(KnowledgeIndexPipeline.digest("ab","c"),KnowledgeIndexPipeline.digest("a","bc"));
    }
    @Test void verificationRequiresExactSetAndEveryBinding() {
        var chunks=List.of(new Chunk("a",0,"一",1,"节"),new Chunk("b",1,"二",2,"节"));
        var g=new Generation("g",1,1,"l","c","m","key",Map.of(),chunks);
        assertDoesNotThrow(()->KnowledgeIndexPipeline.verify(g,new Receipt("g","c","m",List.of("b","a"))));
        for(var bad:List.of(new Receipt("other","c","m",List.of("a","b")),new Receipt("g","other","m",List.of("a","b")),
                new Receipt("g","c","other",List.of("a","b")),new Receipt("g","c","m",List.of("a","a")),new Receipt("g","c","m",List.of("a","z")))) {
            var failure=assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->KnowledgeIndexPipeline.verify(g,bad));assertTrue(failure.permanent());
        }
    }
    @Test void vectorValidationRejectsInvalidOrMissingBatchResults() {
        assertDoesNotThrow(()->KnowledgeIndexPipeline.validate(List.of(List.of(1.0,2.0)),1,2));
        for(var values:List.of(List.<List<Double>>of(),List.of(List.of(Double.NaN,1.0)),List.of(List.of(0.0,0.0)),List.of(List.of(1.0))))
            assertThrows(KnowledgeIndexPipeline.IndexFailure.class,()->KnowledgeIndexPipeline.validate(values,1,2));
    }
}

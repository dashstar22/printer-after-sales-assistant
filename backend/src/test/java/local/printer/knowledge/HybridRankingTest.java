package local.printer.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.printer.knowledge.index.IndexQuery.Hit;
import org.junit.jupiter.api.Test;

class HybridRankingTest {
    @Test void builtinRrfMatchesFormulaAndDoesNotAddIncomparableScores(){
        var rows=HybridRanking.fuse(List.of(new Hit("g","a",0.8),new Hit("g","b",0.7)),List.of(new Hit("g","b",12345),new Hit("g","a",2)));
        assertEquals("a",rows.get(0).chunkId());assertEquals(1.0/61+1.0/62,rows.get(0).fusionScore(),1e-15);
        assertEquals(1,rows.get(0).vectorRank());assertEquals(2,rows.get(0).bm25Rank());assertEquals(2,rows.get(0).bm25Score());
    }
    @Test void duplicatesSingleLaneAndTiesHaveStableIdentities(){
        var rows=HybridRanking.fuse(List.of(new Hit("g","a",0.8),new Hit("g","a",0.7),new Hit("g","b",0.6)),List.of());
        assertEquals(2,rows.size());assertEquals(1.0/62,rows.get(1).fusionScore());assertNull(rows.get(0).bm25Score());
        assertTrue(HybridRanking.fuse(List.of(),List.of()).isEmpty());
        assertThrows(IllegalStateException.class,()->HybridRanking.fuse(List.of(new Hit("g","a",0.8)),List.of(new Hit("other","a",8))));
    }
    @Test void contextNeverTruncatesAConditionAndRetainsDuplicateMapping(){
        String text="警告：关闭电源并拔下插头后，等待设备冷却，再清除卡纸。";
        var units=List.of(new HybridRanking.Unit("a","说明书","页1",text),new HybridRanking.Unit("b","说明书","页1",text),new HybridRanking.Unit("c","长步骤","页2","先断电。"+"步骤".repeat(3000)));
        var result=HybridRanking.context(units,6000);assertEquals(1,result.units().size());assertEquals(text,result.units().get(0).text());assertEquals("a",result.duplicateOf().get("b"));assertTrue(result.limited());assertTrue(result.tokenUpperBound()<=6000);
        assertTrue(HybridRanking.context(units,2).units().isEmpty());
    }
    @Test void maximumFiveWholeUnitsAndUtf8UpperBound(){
        var units=new ArrayList<HybridRanking.Unit>();for(int i=0;i<8;i++)units.add(new HybridRanking.Unit("id"+i,"标题","页码","不同条件与操作"+i));
        assertEquals(5,HybridRanking.context(units,6000).units().size());assertEquals(6,HybridRanking.tokenUpperBound("中文"));
    }
    @Test void explicitModelConflictDoesNotUseSubstringOrSemanticSimilarity(){
        assertFalse(KnowledgeGenerationReader.containsCode("P-1000故障","P-100"));
        assertTrue(KnowledgeGenerationReader.containsCode("打印机 Ｐ－１００ 故障","P-100"));
    }
    @Test void countedContextIsTheActualToolJsonIncludingConditionsAndLocation(){
        var source=new KnowledgeRetrievalService.Source("point",1,1,0,0.9,"警告：先断电。\"带引号的正文\"","标题","key","v1","MODELS",List.of("P-100"),2,"条件与步骤","enterprise_faq","企业FAQ",null,null,null,"仅适用于已关闭设备",true,false);
        String serialized=KnowledgeEvidenceContext.serialized(source);assertTrue(serialized.contains(source.useCondition()));assertTrue(serialized.contains("pageNumber"));assertTrue(serialized.contains("P-100"));
        var unit=new HybridRanking.Unit("chunk","标题","页2",source.content(),serialized);var context=HybridRanking.context(List.of(unit),5744);
        assertEquals(serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,context.tokenUpperBound());
    }
    @Test void identicalBodyDoesNotEraseDifferentSourcesOrApplicabilityConditions(){
        var standard=source(1,"仅适用于已关闭设备");
        var differentCondition=source(1,"仅适用于断电并冷却十分钟的设备");
        var differentSource=source(2,"仅适用于已关闭设备");
        var sources=List.of(standard,differentCondition,differentSource,standard);var units=new ArrayList<HybridRanking.Unit>();
        for(int i=0;i<sources.size();i++){
            var s=sources.get(i);units.add(new HybridRanking.Unit("chunk-"+i,s.title(),"页1",s.content(),KnowledgeEvidenceContext.serialized(s),KnowledgeEvidenceContext.deduplicationScope(s)));
        }
        var context=HybridRanking.context(units,6000);assertEquals(3,context.units().size());
        assertEquals(Map.of("chunk-3","chunk-0"),context.duplicateOf());
        assertTrue(context.units().get(1).context().contains("冷却十分钟"));
    }
    private static KnowledgeRetrievalService.Source source(long document,String condition){
        return new KnowledgeRetrievalService.Source("point-"+document,document,1,0,0.9,"拉出纸盒，再检查进纸口。","相同标题","key","v1","MODELS",List.of("P-100"),1,"章节","enterprise_faq","来源",null,null,null,condition,true,false);
    }
}

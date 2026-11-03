package local.printer.assistant;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;

class ConciseEvidenceAnswerTest {
    final EvidenceAnswerPolicyTest fixture=new EvidenceAnswerPolicyTest();
    String answer(String text,String id){return JsonMapper.builder().build().writeValueAsString(Map.of("kind","KNOWLEDGE","points",List.of(Map.of("text",text,"sourceIds",List.of(id)))));}
    @Test void concisePointsHaveServerMappedCitationsConditionsWarningsAndSnapshots(){
        var source=fixture.source("actual","警告：必须先断电，等待冷却。\n打开外盖。\n若卡纸无法取出，请联系人工。");
        var result=EvidenceAnswerPolicy.assemble(answer("打开外盖。","actual"),List.of(source));
        assertEquals("ANSWERED",result.status());assertEquals(List.of(1),result.points().get(0).citations());
        assertTrue(result.text().contains("必须先断电"));assertTrue(result.text().contains("若卡纸无法取出"));assertTrue(result.text().contains(source.useCondition()));assertEquals(source,result.citations().get(0));
    }
    @Test void validIdDoesNotAuthorizeUnsupportedFactsOrRemovingNegation(){
        var source=fixture.source("actual","不得打开外盖。\n警告：不要跳过确认或猜填联系人。");
        for(String text:List.of("打开外盖。","请拆开定影器。","警告：不要跳过确认或猜填联系人。"))
            assertEquals("REFERENCE_REJECTED",EvidenceAnswerPolicy.assemble(answer(text,"actual"),List.of(source)).status());
        assertEquals("REFERENCE_REJECTED",EvidenceAnswerPolicy.assemble(answer("不得打开外盖。","forged"),List.of(source)).status());
    }
    @Test void excessiveConditionsFailClosedAndDegradedExcerptsRetainSource(){
        var source=fixture.source("actual","警告："+"安".repeat(4000)+"。\n关闭电源。");
        assertEquals("REFERENCE_REJECTED",EvidenceAnswerPolicy.assemble(answer("关闭电源。","actual"),List.of(source)).status());
        var normal=fixture.source("a","先关闭电源。");assertEquals(List.of(normal),EvidenceAnswerPolicy.excerpts(List.of(normal),"EXCERPT_ONLY").citations());
    }
    @Test void formattingWhitespaceAtSentenceBoundariesDoesNotRejectExactQuotes(){
        var source=fixture.source("actual","先断电。  等待至少十分钟。\n  不得打开后盖。  ");
        for(String text:List.of("等待至少十分钟。","不得打开后盖。"))
            assertEquals("ANSWERED",EvidenceAnswerPolicy.assemble(answer(text,"actual"),List.of(source)).status());
        assertEquals("REFERENCE_REJECTED",EvidenceAnswerPolicy.assemble(answer("打开后盖。","actual"),List.of(source)).status());
        assertEquals("REFERENCE_REJECTED",EvidenceAnswerPolicy.assemble(answer("等待五分钟。","actual"),List.of(source)).status());
        var wrapped=fixture.source("wrapped","不得\n  打开后盖。");
        assertEquals("REFERENCE_REJECTED",EvidenceAnswerPolicy.assemble(answer("打开后盖。","wrapped"),List.of(wrapped)).status());
    }
}

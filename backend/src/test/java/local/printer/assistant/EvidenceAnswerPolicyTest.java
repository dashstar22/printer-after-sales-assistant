package local.printer.assistant;

import java.util.*;
import local.printer.knowledge.KnowledgeRetrievalService.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceAnswerPolicyTest {
    Source source(String id,String content){return new Source(id,71,81,0,0.8,content,"已登记资料","key","v2","MODELS",List.of("TEST-A"),
        3,"注意事项","self_authored_simulation","自编模拟企业规则",null,null,null,"仅模拟",true,true);}
    final Source actual=source("actual-current","先核对适用条件。此处没有维修步骤或联系人。");
    @Test void assemblesOnlySelectedActualSourcesAndKeepsMetadataAndWholeSafetyConditions(){
        var unused=source("unused","其他资料");
        var answer=EvidenceAnswerPolicy.assemble("{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"actual-current\"]}",List.of(actual,unused));
        assertEquals("ANSWERED",answer.status());assertEquals(List.of(actual),answer.citations());
        assertTrue(answer.text().contains(actual.content()));assertTrue(answer.text().contains("TEST-A"));
        assertTrue(answer.text().contains("v2"));assertFalse(answer.text().contains(unused.content()));
        assertEquals(3,answer.citations().get(0).pageNumber());assertEquals("注意事项",answer.citations().get(0).section());
    }
    @ParameterizedTest @ValueSource(strings={
        "{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"forged\"]}",
        "{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"actual-current\",\"old-version\"]}",
        "{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"actual-current\",\"actual-current\"]}",
        "{\"kind\":\"KNOWLEDGE\",\"pointIds\":[]}",
        "{\"kind\":\"KNOWLEDGE\",\"pointIds\":[81]}",
        "{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"actual-current\"],\"text\":\"联系人为虚构人员，拆开定影器维修\"}",
        "{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"actual-current\"],\"sourceUrl\":\"https://forged.example\"}",
        "{\"kind\":\"KNOWLEDGE\",\"kind\":\"NO_EVIDENCE\",\"pointIds\":[\"actual-current\"]}",
        "{\"kind\":\"NO_EVIDENCE\",\"text\":\"虚构维修步骤\"}",
        "{\"kind\":\"WORKFLOW\"}","null","[]","not json",
        "{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"actual-current\"]} trailing"
    }) void fabricatedIdsMetadataAndFreeFactsFailClosed(String text){
        var answer=EvidenceAnswerPolicy.assemble(text,List.of(actual));assertEquals("REFERENCE_REJECTED",answer.status());
        assertTrue(answer.citations().isEmpty());assertFalse(answer.text().contains("虚构人员"));assertFalse(answer.text().contains("拆开定影器"));assertFalse(answer.text().contains("forged.example"));
    }
    @Test void previousTurnOrDeviceIdIsNotCurrentEvidence(){
        assertEquals("REFERENCE_REJECTED",EvidenceAnswerPolicy.assemble("{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"actual-current\"]}",List.of()).status());
    }
    @Test void wholeAnswerOverStorageLimitIsRejectedWithoutTrimmingWarnings(){
        var sources=List.of(source("a","安".repeat(2000)),source("b","全".repeat(2000)));
        assertEquals("REFERENCE_REJECTED",EvidenceAnswerPolicy.assemble("{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"a\",\"b\"]}",sources).status());
    }
    @Test void insufficientRelevantEvidenceCanDeclineEvenWithHits(){
        var answer=EvidenceAnswerPolicy.assemble("{\"kind\":\"NO_EVIDENCE\"}",List.of(actual));
        assertEquals("NO_EVIDENCE",answer.status());assertTrue(answer.citations().isEmpty());
        assertNotEquals(answer.text(),EvidenceAnswerPolicy.unavailable().text());
        assertEquals("RETRIEVAL_UNAVAILABLE",EvidenceAnswerPolicy.unavailable().status());
        assertEquals("EVIDENCE_NOT_AUTHORIZED",EvidenceAnswerPolicy.unauthorized().status());
    }
}

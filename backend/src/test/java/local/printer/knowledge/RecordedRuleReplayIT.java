package local.printer.knowledge;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit read-only examination of the existing real publication. Flyway and AI are disabled. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.flyway.enabled=false","AI_ENABLED=false"})
class RecordedRuleReplayIT {
    @Autowired JdbcTemplate jdbc;@Autowired KnowledgeRetrievalService service;
    @Test void admittedTopicReturnsActualFieldChapterFromOriginalPublication()throws Exception{
        String expected=System.getenv("LAYERED_READ_ONLY_DATABASE");assertNotNull(expected);
        assertEquals(expected,jdbc.queryForObject("SELECT DATABASE()",String.class));
        long customer=jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_a'",Long.class);
        long order=jdbc.queryForObject("SELECT MIN(id) FROM orders WHERE customer_id=?",Long.class,customer);
        var result=service.ruleOwnedForAssistant(customer,order,RuleTopicCatalog.Topic.REQUIRED_FIELDS);
        assertEquals("HIT",result.status());assertEquals("RULE_TOPIC_MATCH",result.reason());assertEquals(0.6,result.minScore());
        assertFalse(result.sources().isEmpty());
        for(var source:result.sources()){
            assertNull(source.score());assertEquals("business-supplement-2026-10-28-v1",source.version());
            assertEquals(RuleTopicCatalog.Topic.REQUIRED_FIELDS.section(),source.section());assertTrue(source.content().contains("四个文本字段"));
        }
        var receipt=Map.of("status",result.status(),"reason",result.reason(),"minScore",result.minScore(),"sources",result.sources());
        Path output=Path.of(System.getenv("LAYERED_READ_ONLY_RECEIPT"));assertTrue(output.toAbsolutePath().toString().contains(".local-validation"));
        Files.writeString(output,JsonMapper.builder().build().writeValueAsString(receipt));
    }
}

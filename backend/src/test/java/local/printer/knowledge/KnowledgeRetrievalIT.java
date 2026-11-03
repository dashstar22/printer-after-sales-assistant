package local.printer.knowledge;

import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import local.printer.order.OrderService;
import local.printer.seed.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MySQL ownership checks; no substitute embedding/vector success. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class KnowledgeRetrievalIT {
    @Autowired KnowledgeRetrievalService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedService seed;
    @BeforeEach void isolation(){
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_retrieval_it_"));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"));
    }
    @Test void unicodeTrimCountAndNoSurrogateSplit(){
        assertEquals("中文😀",KnowledgeRetrievalService.text("\u3000\u00a0中文😀\u2003",3));
        assertEquals(1000,KnowledgeRetrievalService.text("😀".repeat(1000),1000).codePointCount(0,2000));
    }
    @Test void invalidQueryHasSpecificCodeBeforeDependencies(){
        for(String text:Arrays.asList(null,"","\u3000\u00a0","字".repeat(1001),"\0","\uD800")){
            var error=assertThrows(KnowledgeFailure.class,()->service.searchModel("TEST-A",text));
            assertEquals("KNOWLEDGE_QUERY_INVALID",error.getMessage());assertEquals(2,error.exitCode());
        }
    }
    @Test void invalidModelHasSpecificCodeBeforeDependencies(){
        for(String model:Arrays.asList(null,"","A".repeat(65),"A\nB","\0")){
            var error=assertThrows(KnowledgeFailure.class,()->service.searchModel(model,"模拟查询"));
            assertEquals("KNOWLEDGE_QUERY_INVALID",error.getMessage());assertEquals(2,error.exitCode());
        }
    }
    @Test void ownershipUsesRealOrderAndCannotReachDependencyOnForeignOrder(){
        var p=new SeedPasswords("isolated-t022-customer-a","isolated-t022-customer-b","isolated-t022-staff");seed.seed(p);
        long a=jdbc.queryForObject("SELECT id FROM users WHERE username='demo_customer_a'",Long.class);
        long bOrder=jdbc.queryForObject("SELECT o.id FROM orders o JOIN users u ON u.id=o.customer_id WHERE u.username='demo_customer_b'",Long.class);
        assertThrows(OrderService.NotFound.class,()->service.searchOwned(a,bOrder,"模拟查询"));
        assertThrows(OrderService.NotFound.class,()->service.searchOwned(a,Long.MAX_VALUE,"模拟查询"));
        long owned=jdbc.queryForObject("SELECT id FROM orders WHERE customer_id=?",Long.class,a);
        assertEquals("KNOWLEDGE_QUERY_INVALID",assertThrows(KnowledgeFailure.class,()->service.searchOwned(a,owned," ")).getMessage());
    }
    @Test void sourceDoesNotPrintBodyInToString(){
        var s=new KnowledgeRetrievalService.Source("uuid",1,1,0,0.8,"private-body","title","key","v1","GENERAL",List.of(),null,null,
            "self_authored_simulation","自编模拟企业规则",null,null,null,"condition",true,true);
        assertFalse(s.toString().contains("private-body"));
    }
    @Test void additiveMigrationsAndNoAutomaticKnowledgeRows(){
        assertEquals(local.printer.MigrationAssertions.versions(),jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunks",Integer.class));
    }
    @Test void createSelfAuthoredPdfFixtureWithPhysicalPageTwo()throws Exception{
        String target=System.getenv("T022_PDF_FIXTURE");assertNotNull(target);
        Path path=Path.of(target);assertTrue(path.toAbsolutePath().toString().contains(".local-validation"));assertFalse(Files.exists(path));
        try(var document=new PDDocument()){
            document.addPage(new PDPage());var page=new PDPage();document.addPage(page);
            try(var stream=new PDPageContentStream(document,page)){
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                stream.newLineAtOffset(40,700);stream.showText("Self-authored simulated rule: TEST-PDF local observation only. No service promise.");stream.endText();
            }document.save(path.toFile());
        }assertTrue(Files.size(path)>0);
    }
}

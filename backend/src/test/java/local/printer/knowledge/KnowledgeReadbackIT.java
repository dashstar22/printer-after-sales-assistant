package local.printer.knowledge;

import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import local.printer.knowledge.KnowledgeRows.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reads results created by independent Java CLI. Never prints/asserts vendor text values. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class KnowledgeReadbackIT {
    @Autowired KnowledgeMapper mapper;@Autowired JdbcTemplate jdbc;
    Path root;
    @BeforeEach void isolation(){
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_extract_cli_it_"));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"));
        root=Path.of("").toAbsolutePath();if(!Files.exists(root.resolve("docs")))root=root.getParent();
    }
    String reconstruct(List<Chunk> rows){
        assertFalse(rows.isEmpty());var result=new StringBuilder(rows.get(0).content());
        for(int i=1;i<rows.size();i++){
            String previous=rows.get(i-1).content(),current=rows.get(i).content();
            int begin=previous.offsetByCodePoints(0,previous.codePointCount(0,previous.length())-100);
            assertTrue(previous.substring(begin).equals(current.substring(0,current.offsetByCodePoints(0,100))),"overlap differs; no text emitted");
            result.append(current.substring(current.offsetByCodePoints(0,100)));
        }return result.toString();
    }
    @Test void officialPages153And156MatchFullLocalReferenceAndWarningConditions()throws Exception{
        var source=mapper.document("brother-hll2350dw-oug-d","D");assertNotNull(source);var chunks=mapper.chunks(source.id());
        Path privateDir=root.resolve(".local-validation/t020-official-readback/"+jdbc.queryForObject("SELECT DATABASE()",String.class));Files.createDirectories(privateDir);
        try(var document=Loader.loadPDF(root.resolve(source.localFile()).toFile())){
            var stripper=new PDFTextStripper();stripper.setSortByPosition(true);stripper.setParagraphEnd("\n\n");stripper.setLineSeparator("\n");stripper.setPageStart("");stripper.setPageEnd("");
            for(int page:new int[]{153,156}){
                stripper.setStartPage(page);stripper.setEndPage(page);String reference=KnowledgeTextExtractor.normalize(stripper.getText(document));
                final int selected=page;String saved=reconstruct(chunks.stream().filter(c->Objects.equals(c.pageNumber(),selected)).toList());
                Files.writeString(privateDir.resolve("page-"+page+"-reference.txt"),reference);Files.writeString(privateDir.resolve("page-"+page+"-reconstructed.txt"),saved);
                assertTrue(reference.equals(saved),"physical page text/conditions differ; inspect ignored local files");
                String flat=saved.replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
                if(page==153)assertTrue(flat.contains("paper is jammed in the paper tray"),"registered section not located on physical page153");
                else {assertTrue(flat.contains("hot surface"),"registered heat warning not retained");assertTrue(flat.contains("cool") && flat.contains("10 minutes"),"cooling condition not retained");}
            }
        }
    }
    @Test void officialWholeDocumentEveryNonemptyPhysicalPageReconstructsExactly()throws Exception{
        var source=mapper.document("brother-hll2350dw-oug-d","D");var rows=mapper.chunks(source.id());assertFalse(rows.isEmpty());
        for(int i=0;i<rows.size();i++){var row=rows.get(i);assertEquals(i,row.chunkIndex());assertNull(row.section());assertTrue(row.content().codePointCount(0,row.content().length())<=600);}
        try(var document=Loader.loadPDF(root.resolve(source.localFile()).toFile())){
            var stripper=new PDFTextStripper();stripper.setSortByPosition(true);stripper.setParagraphEnd("\n\n");stripper.setLineSeparator("\n");stripper.setPageStart("");stripper.setPageEnd("");
            for(int page=1;page<=document.getNumberOfPages();page++){
                stripper.setStartPage(page);stripper.setEndPage(page);String expected=KnowledgeTextExtractor.normalize(stripper.getText(document));final int p=page;
                var selected=rows.stream().filter(c->Objects.equals(c.pageNumber(),p)).toList();
                if(expected.isEmpty())assertTrue(selected.isEmpty());else assertTrue(expected.equals(reconstruct(selected)),"page coverage differs; no vendor text emitted");
            }
        }
        assertEquals("PENDING",source.status());assertFalse(source.active());assertNull(source.contentHash());assertFalse(source.selfAuthored());assertFalse(source.simulated());
        assertFalse(source.externalUploadAllowed());assertFalse(source.distributionAllowed());assertTrue(source.modelsJson().contains("HL-L2350DW"));
    }
    @Test void selfAuthoredMarkdownRetainsChapterContentAndSimulationMetadata()throws Exception{
        var source=mapper.document("simulated-repair-workflow","simulation-2026-10-27");assertTrue(source.selfAuthored());assertTrue(source.simulated());assertEquals("GENERAL",source.modelScope());
        var rows=mapper.chunks(source.id());assertTrue(rows.stream().allMatch(c->c.pageNumber()==null && c.section().equals("自编模拟企业规则：普通报修操作说明")));
        String expected=KnowledgeTextExtractor.normalize(Files.readString(root.resolve(source.localFile())));
        assertTrue(expected.equals(reconstruct(rows)),"self authored markdown coverage differs");
        assertEquals("PENDING",source.status());assertFalse(source.active());assertNull(source.contentHash());
    }
}

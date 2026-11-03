package local.printer.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import local.printer.administration.AdminFailure;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class KnowledgePreprocessorTest {
    private byte[] md(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static KnowledgePreprocessor.Result process(byte[] bytes,String ext){return KnowledgePreprocessor.process(bytes,ext,500,50);}
    @Test void unicodeBoundariesLosslessWarningCodeStepsAndStableOffsets() {
        String text="# 维护😀\n\n警告：断开电源后再检查。\n1. 型号 PX-100 错误码 E-1100 电压220V。\n"+("中文𠀀😀，保留前提；然后检查。\n".repeat(120));
        var a=process(md(text),"md");var b=process(md(text),"md");assertEquals(a,b);
        var coverage=new BitSet(a.text().codePointCount(0,a.text().length()));
        for(var c:a.chunks()) {
            assertEquals(c.text(),KnowledgePreprocessor.sub(a.text(),c.start(),c.end()));
            assertTrue(c.end()-c.start()<=500);assertFalse(c.text().codePoints().anyMatch(cp->cp>=0xD800&&cp<=0xDFFF));
            coverage.set(c.start(),c.end());assertEquals("维护😀",c.section());
        }
        assertEquals(a.text().codePointCount(0,a.text().length()),coverage.cardinality());
        assertTrue(a.chunks().get(0).text().contains("警告：断开电源"));
        assertTrue(a.chunks().get(0).text().contains("E-1100"));
        assertTrue(a.chunks().size()>1);
        assertTrue(a.chunks().get(1).start()<a.chunks().get(0).end());
    }
    @Test void paragraphThenLineThenChineseSentenceThenCommaBoundaries() {
        for(String separator:List.of("\n\n","\n","。","；","，")) {
            String text="甲".repeat(320)+separator+"乙".repeat(250);
            var p=process(md(text),"md");assertEquals(320+separator.length(),p.chunks().get(0).end());
            assertEquals(text,p.text());
        }
    }
    @Test void longAsciiAndAstralNeverStall() {
        for(String text:List.of("A".repeat(20000),"😀".repeat(20000))) {
            var p=process(md(text),"md");assertTrue(p.chunks().size()<100);
            assertEquals(text.codePointCount(0,text.length()),p.chunks().get(p.chunks().size()-1).end());
        }
    }
    @Test void markdownFenceHeadingsAndSimpleTableAreNotLost() {
        String text="# A\n\n```md\n## not-heading\n```\n\n|编码|说明|\n|E-11|断电|\n\n## B\n条件不满足请停止。";
        var p=process(md(text),"md");assertEquals(2,p.units());assertTrue(p.text().contains("## not-heading"));
        assertTrue(p.chunks().get(0).text().contains("|E-11|断电|"));assertEquals("A > B",p.chunks().get(1).section());
    }
    @Test void realDocxHeadingAndOrderedSimpleTable()throws Exception {
        byte[] bytes;
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            var h=doc.createParagraph();h.setStyle("Heading1");h.createRun().setText("检修注意");
            doc.createParagraph().createRun().setText("警告：先断电，错误码E-100。");
            var table=doc.createTable(2,2);table.getRow(0).getCell(0).setText("型号");table.getRow(0).getCell(1).setText("电压");
            table.getRow(1).getCell(0).setText("PX100");table.getRow(1).getCell(1).setText("220V");doc.write(out);bytes=out.toByteArray();
        }
        fixture("simple.docx",bytes);var p=process(bytes,"docx");assertEquals("检修注意",p.chunks().get(0).section());
        assertTrue(p.text().contains("型号\t电压"));assertTrue(p.text().contains("PX100\t220V"));assertTrue(p.text().contains("警告：先断电"));
    }
    @Test void actualDocxDecimalNumberingPreserved()throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            var numbering=doc.createNumbering();
            var abstractNum=org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum.Factory.newInstance();abstractNum.setAbstractNumId(java.math.BigInteger.ZERO);
            var level=abstractNum.addNewLvl();level.setIlvl(java.math.BigInteger.ZERO);level.addNewStart().setVal(java.math.BigInteger.ONE);
            level.addNewNumFmt().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat.DECIMAL);level.addNewLvlText().setVal("%1.");
            var num=numbering.addNum(numbering.addAbstractNum(new XWPFAbstractNum(abstractNum)));
            for(String step:List.of("先断电","再检查E-100")){var p=doc.createParagraph();p.setNumID(num);p.setNumILvl(java.math.BigInteger.ZERO);p.createRun().setText(step);}
            doc.write(out);assertTrue(process(out.toByteArray(),"docx").text().contains("1. 先断电\n\n2. 再检查E-100"));
        }
    }
    @Test void realPdfPagePositionsAndBlankScannedDocument()throws Exception {
        byte[] bytes;
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(int i=0;i<2;i++) {
                var page=new PDPage();doc.addPage(page);
                try(var stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(50,700);stream.showText("WARNING power off. Page "+(i+1)+" E-100 220V");stream.endText();}
            }
            doc.save(out);bytes=out.toByteArray();
        }
        fixture("text.pdf",bytes);var p=process(bytes,"pdf");assertEquals(2,p.chunks().size());assertEquals(1,p.chunks().get(0).page());assertEquals(2,p.chunks().get(1).pageEnd());
        assertTrue(p.chunks().get(1).start()>p.chunks().get(0).end());
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());doc.save(out);fixture("blank.pdf",out.toByteArray());
            assertEquals("KNOWLEDGE_NO_TEXT",assertThrows(AdminFailure.class,()->process(out.toByteArray(),"pdf")).code);
        }
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
            var page=new PDPage();doc.addPage(page);var bitmap=new java.awt.image.BufferedImage(100,100,java.awt.image.BufferedImage.TYPE_INT_RGB);
            try(var stream=new PDPageContentStream(doc,page)){stream.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc,bitmap),10,10);}
            doc.save(out);fixture("scan.pdf",out.toByteArray());
            assertEquals("KNOWLEDGE_NO_TEXT",assertThrows(AdminFailure.class,()->process(out.toByteArray(),"pdf")).code);
        }
    }
    @Test void limitsEmptyMalformedSignatureAndEncryptedPdf()throws Exception {
        assertThrows(AdminFailure.class,()->process(new byte[20*1024*1024+1],"md"));
        assertThrows(AdminFailure.class,()->process(md("甲".repeat(2_000_001)),"md"));
        assertThrows(AdminFailure.class,()->process(md(" \n\t"),"md"));
        assertThrows(AdminFailure.class,()->process(md("%PDF-fake"),"pdf"));
        assertThrows(AdminFailure.class,()->process(md("%PDF-fake"),"md"));
        assertThrows(AdminFailure.class,()->process(md("not a zip"),"docx"));
        assertThrows(AdminFailure.class,()->process(new byte[]{(byte)0xFF},"md"));
        assertThrows(AdminFailure.class,()->process(md("# A\nx\n".repeat(10001)),"md"));
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(int i=0;i<501;i++)doc.addPage(new PDPage());doc.save(out);
            assertEquals("RESOURCE_LIMIT",assertThrows(AdminFailure.class,()->process(out.toByteArray(),"pdf")).code);
        }
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());doc.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner","secret",new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));doc.save(out);
            assertEquals("KNOWLEDGE_PDF_UNREADABLE",assertThrows(AdminFailure.class,()->process(out.toByteArray(),"pdf")).code);
        }
    }
    @Test void zipBombAndTraversalAndEmptyDocxAndNestedTableRejected()throws Exception {
        try(var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write(new byte[2*1024*1024]);zip.closeEntry();zip.finish();
            fixture("bomb.docx",out.toByteArray());assertEquals("KNOWLEDGE_DOCX_COMPRESSION_LIMIT",assertThrows(AdminFailure.class,()->process(out.toByteArray(),"docx")).code);
        }
        try(var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("../word/document.xml"));zip.write(md("x"));zip.closeEntry();zip.finish();
            assertThrows(AdminFailure.class,()->process(out.toByteArray(),"docx"));
        }
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.write(out);fixture("empty.docx",out.toByteArray());assertEquals("KNOWLEDGE_NO_TEXT",assertThrows(AdminFailure.class,()->process(out.toByteArray(),"docx")).code);
        }
    }
    @Test void permissionsCannotCrossPurposeOrConfigurationAndDefaultDenied() {
        var json=JsonMapper.builder().build();var policy=new KnowledgePurposePolicy(new MockEnvironment().withProperty("app.knowledge.processing-configurations","embedding:dev,rerank:dev,chat:dev"));
        var grants=policy.validate(json.readTree("{\"embedding\":{\"allowed\":true,\"configurationId\":\"dev\"}}"),json);
        policy.require(grants,"embedding","dev");
        assertThrows(AdminFailure.class,()->policy.require(grants,"chat","dev"));assertThrows(AdminFailure.class,()->policy.require(grants,"rerank","dev"));
        assertThrows(AdminFailure.class,()->policy.require(grants,"embedding","other"));
        assertThrows(AdminFailure.class,()->policy.validate(json.readTree("{\"chat\":{\"allowed\":true,\"configurationId\":\"https://attacker\"}}"),json));
        assertThrows(AdminFailure.class,()->policy.require(policy.validate(null,json),"embedding","dev"));
    }
    private static void fixture(String name,byte[] bytes)throws IOException {
        String dir=System.getProperty("knowledge.fixture.directory");if(dir!=null){Path path=Path.of(dir);Files.createDirectories(path);Files.write(path.resolve(name),bytes);}
    }
}

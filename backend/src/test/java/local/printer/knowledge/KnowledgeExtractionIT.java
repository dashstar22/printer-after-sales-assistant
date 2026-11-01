package local.printer.knowledge;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import local.printer.knowledge.KnowledgeRows.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL and local self-authored fixtures. No vendor text in test output. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="app.knowledge.maintenance-enabled=true")
@Import(KnowledgeExtractionIT.FixtureConfiguration.class)
class KnowledgeExtractionIT {
    @Autowired KnowledgeMapper mapper;
    @Autowired KnowledgeRegistrationService registration;
    @Autowired KnowledgeExtractionService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlBoundary boundary;
    Path root,dir;
    @BeforeEach void prepare()throws Exception{
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_extraction_it_"));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"));
        root=Path.of("").toAbsolutePath();if(!Files.exists(root.resolve("docs")))root=root.getParent();
        dir=root.resolve(".local-validation/t020-fixtures/"+UUID.randomUUID());Files.createDirectories(dir);
        boundary.reset();
    }
    long register(Path file)throws Exception{
        String id="fixture-"+UUID.randomUUID();
        var d=new LinkedHashMap<String,Object>();
        d.put("id",id);d.put("title","自编隔离提取测试材料");d.put("source_type","self_authored_simulation");d.put("source","自编模拟企业规则");
        d.put("self_authored",true);d.put("is_simulated",true);d.put("official_manual",false);d.put("model_scope","GENERAL");d.put("selected_models",List.of());
        d.put("version","fixture-v1");d.put("use_condition","仅隔离测试的自编材料，无真实企业服务承诺");
        d.put("distribution_allowed",true);d.put("external_upload_allowed",false);d.put("local_file",root.relativize(file).toString());
        Path manifest=dir.resolve(id+".json");Files.writeString(manifest,JsonMapper.builder().build().writeValueAsString(Map.of("checked_on","2026-10-27","documents",List.of(d))));
        assertEquals(1,registration.register(root,root.relativize(manifest).toString(),List.of(id)));
        return mapper.document(id,"fixture-v1").id();
    }
    long markdown(String text)throws Exception{Path p=dir.resolve(UUID.randomUUID()+".md");Files.writeString(p,text);return register(p);}
    KnowledgeExtractionService.Result extract(long id){return service.extract(root,id,600,100);}
    void failure(String code,int exit,Runnable run){var e=assertThrows(KnowledgeFailure.class,run::run);assertEquals("KNOWLEDGE_"+code,e.getMessage());assertEquals(exit,e.exitCode());}
    String join(List<String> parts,int overlap){
        var b=new StringBuilder(parts.get(0));for(int i=1;i<parts.size();i++){String s=parts.get(i);b.append(s.substring(s.offsetByCodePoints(0,overlap)));}return b.toString();
    }
    Path pdf(boolean image,boolean blank,boolean mixed)throws Exception{
        Path file=dir.resolve(UUID.randomUUID()+".pdf");
        try(var d=new PDDocument()){
            var page=new PDPage();d.addPage(page);
            try(var stream=new PDPageContentStream(d,page)){
                if(image){var picture=LosslessFactory.createFromImage(d,new BufferedImage(12,12,BufferedImage.TYPE_INT_RGB));stream.drawImage(picture,50,50);}
                else if(!blank){stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(40,700);stream.showText("Self authored PDF page one fixture.");stream.endText();}
            }
            if(mixed){d.addPage(new PDPage());var third=new PDPage();d.addPage(third);try(var s=new PDPageContentStream(d,third)){s.beginText();s.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);s.newLineAtOffset(40,700);s.showText("Self authored PDF physical page three.");s.endText();}}
            d.save(file.toFile());
        }return file;
    }
    @Test void markdownHierarchyListsCodeFencesAndNoFakePreamble()throws Exception{
        long id=markdown("\ufeff前置说明\r\n\r\n# 总则\r\n\r\n- 条目一\r\n- 条目二\r\n\r\n## 操作\r\n\r\n```text\r\n# 代码不是章节\r\n```\r\n\r\n结果说明");
        var before=mapper.documentById(id);var result=extract(id);var rows=mapper.chunks(id);
        assertEquals(3,result.units());assertEquals(3,rows.size());assertNull(rows.get(0).section());assertEquals("总则",rows.get(1).section());
        assertEquals("总则 > 操作",rows.get(2).section());assertTrue(rows.get(1).content().contains("- 条目一\n- 条目二"));
        assertTrue(rows.get(2).content().contains("# 代码不是章节"));assertTrue(rows.stream().allMatch(r->r.pageNumber()==null));
        assertEquals(before,mapper.documentById(id));
    }
    @Test void setextAndTildeFenceHierarchy()throws Exception{
        long id=markdown("总则\n====\n\n规则。\n\n子章\n----\n\n~~~\n## 非标题\n~~~\n\n结束。");extract(id);
        var rows=mapper.chunks(id);assertEquals(2,rows.size());assertEquals("总则 > 子章",rows.get(1).section());assertTrue(rows.get(1).content().contains("## 非标题"));
    }
    @Test void unicodeLongParagraphOverlapTailAndReconstruction()throws Exception{
        String text="中文🖨".repeat(721)+"尾段结束";
        var parts=KnowledgeTextExtractor.split(text,600,100);
        assertEquals(text,join(parts,100));assertTrue(parts.size()>3);
        for(int i=0;i<parts.size();i++){
            String s=parts.get(i);assertTrue(s.codePointCount(0,s.length())<=600);assertFalse(s.contains("\ufffd"));
            if(i>0){String prev=parts.get(i-1);assertEquals(prev.substring(prev.offsetByCodePoints(0,prev.codePointCount(0,prev.length())-100)),s.substring(0,s.offsetByCodePoints(0,100)));}
        }
        long id=markdown(text);extract(id);assertEquals(parts,mapper.chunks(id).stream().map(Chunk::content).toList());
    }
    @Test void paragraphBoundaryAndZeroOverlap(){
        String text="甲".repeat(350)+"\n\n"+"乙".repeat(350);
        var parts=KnowledgeTextExtractor.split(text,600,100);assertTrue(parts.get(0).endsWith("\n\n"));assertEquals(text,join(parts,100));
        assertEquals("甲".repeat(600),KnowledgeTextExtractor.split("甲".repeat(600),600,0).get(0));
        assertEquals(text,String.join("",KnowledgeTextExtractor.split(text,600,0)));
        assertTrue(KnowledgeTextExtractor.split("\u3000\u00a0\n",600,100).isEmpty());
    }
    @Test void physicalPdfPagesAndMixedEmptyPageAreNotRenumbered()throws Exception{
        long id=register(pdf(false,false,true));var result=extract(id);var rows=mapper.chunks(id);
        assertEquals(1,result.emptyPages());assertEquals(List.of(1,3),rows.stream().map(Chunk::pageNumber).toList());
        assertTrue(rows.get(1).content().contains("physical page three"));assertTrue(rows.stream().allMatch(r->r.section()==null));
    }
    @Test void imageOnlyPdfFailsWithNoTextAndNoPartialRows()throws Exception{
        long id=register(pdf(true,false,false));failure("NO_TEXT",2,()->extract(id));assertEquals(0,mapper.chunkCount(id));
    }
    @Test void blankPdfFailsWithoutClaimingScanDetection()throws Exception{
        long id=register(pdf(false,true,false));failure("NO_TEXT",2,()->extract(id));assertEquals(0,mapper.chunkCount(id));
    }
    @Test void corruptPdfHasSpecificFailure()throws Exception{
        Path p=dir.resolve("broken.pdf");Files.writeString(p,"self authored damaged PDF fixture");long id=register(p);
        failure("PDF_UNREADABLE",2,()->extract(id));assertEquals(0,mapper.chunkCount(id));
    }
    @Test void passwordRequiredPdfFailsWithoutPartialRows()throws Exception{
        Path file=dir.resolve("password-required.pdf");
        try(var document=new PDDocument()){
            document.addPage(new PDPage());
            var permissions=new org.apache.pdfbox.pdmodel.encryption.AccessPermission();
            var protection=new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                UUID.randomUUID().toString(),UUID.randomUUID().toString(),permissions);
            protection.setEncryptionKeyLength(128);document.protect(protection);document.save(file.toFile());
        }
        long id=register(file);var before=mapper.documentById(id);
        failure("PDF_UNREADABLE",2,()->extract(id));assertEquals(0,mapper.chunkCount(id));
        assertEquals(before,mapper.documentById(id));
        Files.writeString(dir.resolve("password-cli-path.txt"),root.relativize(file).toString());
    }
    @Test void blankAndTruncatedToZeroMarkdownFail()throws Exception{
        long id=markdown("\u3000 \n\u00a0");failure("NO_TEXT",2,()->extract(id));assertEquals(0,mapper.chunkCount(id));
        long other=markdown("临时自编文本");Files.write(root.resolve(mapper.documentById(other).localFile()),new byte[0]);failure("NO_TEXT",2,()->extract(other));
    }
    @Test void invalidUtf8AndNulAreRejected()throws Exception{
        long id=markdown("初始自编文本");Path p=root.resolve(mapper.documentById(id).localFile());Files.write(p,new byte[]{(byte)0xc3,0x28});failure("TEXT_INVALID",2,()->extract(id));
        Files.writeString(p,"文字\0文字");failure("TEXT_INVALID",2,()->extract(id));assertEquals(0,mapper.chunkCount(id));
    }
    @Test void missingPathAndUnsupportedFormatHaveSpecificFailures()throws Exception{
        long id=markdown("自编资料");jdbc.update("UPDATE knowledge_documents SET local_file=? WHERE id=?","../outside.md",id);failure("LOCAL_FILE_INVALID",2,()->extract(id));
        Path p=dir.resolve("file.txt");Files.writeString(p,"自编文本");jdbc.update("UPDATE knowledge_documents SET local_file=? WHERE id=?",root.relativize(p).toString(),id);
        failure("FORMAT_UNSUPPORTED",2,()->extract(id));assertEquals(0,mapper.chunkCount(id));
    }
    @Test void resourceAndParameterLimitsDoNotWrite()throws Exception{
        long id=markdown("初始自编资料");Path p=root.resolve(mapper.documentById(id).localFile());
        Files.writeString(p,"甲".repeat(KnowledgeTextExtractor.MAX_TEXT+1));failure("LIMIT_EXCEEDED",2,()->extract(id));
        failure("COMMAND_INVALID",2,()->service.extract(root,id,99,0));failure("COMMAND_INVALID",2,()->service.extract(root,id,600,600));assertEquals(0,mapper.chunkCount(id));
    }
    @Test void repeatAndStateConflictPreserveExistingRows()throws Exception{
        long id=markdown("自编重复执行测试。".repeat(120));extract(id);var before=mapper.chunks(id);var doc=mapper.documentById(id);
        failure("EXTRACTION_CONFLICT",4,()->extract(id));assertEquals(before,mapper.chunks(id));assertEquals(doc,mapper.documentById(id));
        long other=markdown("状态冲突的自编材料");jdbc.update("UPDATE knowledge_documents SET status='PROCESSING' WHERE id=?",other);
        failure("EXTRACTION_CONFLICT",4,()->extract(other));assertEquals(0,mapper.chunkCount(other));
    }
    @Test void missingDocumentHasSpecificFailure(){failure("DOCUMENT_NOT_FOUND",2,()->extract(Long.MAX_VALUE));}
    @Test void actualSecondInsertForeignKeyFailureRollsBackWholeBatchAndCanRetry()throws Exception{
        long unrelated=markdown("独立已处理的自编材料");extract(unrelated);var frozen=mapper.chunks(unrelated);
        long id=markdown("自编事务回滚材料。".repeat(150));var doc=mapper.documentById(id);boundary.failSecond.set(true);
        failure("DATABASE_ERROR",3,()->extract(id));assertEquals(1452,boundary.error.get());assertEquals("23000",boundary.state);
        assertTrue(boundary.constraintSeen);assertEquals(0,mapper.chunkCount(id));assertEquals(doc,mapper.documentById(id));assertEquals(frozen,mapper.chunks(unrelated));
        boundary.failSecond.set(false);assertTrue(extract(id).chunks()>1);
    }
    @Test void twoRealMysqlTransactionsOneWinnerNoDuplicateChunks()throws Exception{
        long id=markdown("自编并发测试材料。".repeat(180));boundary.arrived=new CountDownLatch(2);boundary.go=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try{
            Callable<Integer> task=()->{try{extract(id);return 0;}catch(KnowledgeFailure f){return f.exitCode();}};
            var a=pool.submit(task);var b=pool.submit(task);assertTrue(boundary.arrived.await(10,TimeUnit.SECONDS));boundary.go.countDown();
            assertEquals(List.of(0,4),java.util.stream.Stream.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)).sorted().toList());
            var rows=mapper.chunks(id);assertTrue(rows.size()>1);assertEquals(rows.size(),rows.stream().map(Chunk::chunkIndex).distinct().count());
        }finally{boundary.go.countDown();pool.shutdownNow();}
    }
    @Test void changedMetadataBetweenReadAndLockIsRejected()throws Exception{
        long id=markdown("自编元数据并发变更材料");boundary.arrived=new CountDownLatch(1);boundary.go=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try{
            var future=pool.submit(()->{try{extract(id);return 0;}catch(KnowledgeFailure f){return f.exitCode();}});
            assertTrue(boundary.arrived.await(10,TimeUnit.SECONDS));jdbc.update("UPDATE knowledge_documents SET use_condition='隔离夹具变更后的模拟使用限制' WHERE id=?",id);
            boundary.go.countDown();assertEquals(4,future.get(20,TimeUnit.SECONDS));assertEquals(0,mapper.chunkCount(id));
        }finally{boundary.go.countDown();pool.shutdownNow();}
    }
    @Test void generateActualLocalCliFixturesAndVerifyResourceBoundaries()throws Exception{
        var inputs=new LinkedHashMap<String,String>();
        inputs.put("image_pdf",root.relativize(pdf(true,false,false)).toString());inputs.put("blank_pdf",root.relativize(pdf(false,true,false)).toString());
        inputs.put("mixed_pdf",root.relativize(pdf(false,false,true)).toString());
        Path bad=dir.resolve("corrupt.pdf");Files.writeString(bad,"self authored corrupt PDF");inputs.put("corrupt_pdf",root.relativize(bad).toString());
        Path blank=dir.resolve("blank.md");Files.writeString(blank," \n\u3000\u00a0");inputs.put("blank_md",root.relativize(blank).toString());
        Path invalid=dir.resolve("invalid.md");Files.write(invalid,new byte[]{(byte)0xc3,0x28});inputs.put("invalid_utf8",root.relativize(invalid).toString());
        Path large=dir.resolve("large.md");try(var f=new java.io.RandomAccessFile(large.toFile(),"rw")){f.setLength(KnowledgeTextExtractor.MAX_BYTES+1L);}
        inputs.put("oversized_file",root.relativize(large).toString());
        failure("LIMIT_EXCEEDED",2,()->KnowledgeTextExtractor.extract(root,root.relativize(large).toString(),600,100));
        failure("LIMIT_EXCEEDED",2,()->KnowledgeTextExtractor.split("甲".repeat(20_000),100,99));
        Path pages=dir.resolve("too-many.pdf");try(var d=new PDDocument()){for(int i=0;i<501;i++)d.addPage(new PDPage());d.save(pages.toFile());}
        inputs.put("too_many_pages",root.relativize(pages).toString());failure("LIMIT_EXCEEDED",2,()->KnowledgeTextExtractor.extract(root,root.relativize(pages).toString(),600,100));
        Files.writeString(dir.resolve("cli-inputs.json"),JsonMapper.builder().build().writeValueAsString(inputs));
    }
    @TestConfiguration(proxyBeanMethods=false) static class FixtureConfiguration{@Bean SqlBoundary boundary(){return new SqlBoundary();}}
    @Intercepts({@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}),
        @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class})})
    static class SqlBoundary implements Interceptor{
        final AtomicBoolean failSecond=new AtomicBoolean();final AtomicInteger calls=new AtomicInteger(),error=new AtomicInteger();String state;boolean constraintSeen;
        volatile CountDownLatch arrived,go;
        void reset(){failSecond.set(false);calls.set(0);error.set(0);state=null;constraintSeen=false;arrived=null;go=null;}
        @Override public Object intercept(Invocation invocation)throws Throwable{
            String name=((MappedStatement)invocation.getArgs()[0]).getId();
            if(name.endsWith("KnowledgeMapper.lockDocument")&&arrived!=null){arrived.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture barrier timeout");}
            if(name.endsWith("KnowledgeMapper.insertChunk")&&failSecond.get()&&calls.incrementAndGet()==2){
                var old=(NewRow<Chunk>)invocation.getArgs()[1];var c=old.getRow();invocation.getArgs()[1]=new NewRow<>(new Chunk(null,Long.MAX_VALUE,c.chunkIndex(),c.content(),c.pageNumber(),c.section()));
            }
            try{return invocation.proceed();}catch(Throwable failure){for(Throwable c=failure;c!=null;c=c.getCause())if(c instanceof SQLException s){error.set(s.getErrorCode());state=s.getSQLState();constraintSeen=s.getMessage().contains("fk_knowledge_chunks_document");}throw failure;}
        }
    }
}

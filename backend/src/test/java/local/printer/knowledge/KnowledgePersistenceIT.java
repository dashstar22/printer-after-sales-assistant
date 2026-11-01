package local.printer.knowledge;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import local.printer.knowledge.KnowledgeRows.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL fixtures only, rolled back. These are not production extraction/chunking. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties="app.knowledge.maintenance-enabled=true")
@Transactional
class KnowledgePersistenceIT {
    @Autowired KnowledgeMapper mapper;
    @Autowired JdbcTemplate jdbc;
    Path root;
    @BeforeEach void isolation() throws Exception {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("printer_knowledge_it_"));
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("root@"));
        root=Path.of("").toAbsolutePath();if(!root.resolve("docs/sources/registry.json").toFile().exists())root=root.getParent();
    }
    Document fixture(String id) {
        return KnowledgeManifest.read(root,"docs/sources/registry.json",List.of(id)).get(0);
    }
    long insert(String source) {
        Document d=fixture(source);
        d=new Document(null,"fixture-"+UUID.randomUUID(),d.version(),d.title(),d.sourceType(),d.selfAuthored(),d.simulated(),
            d.modelScope(),d.modelsJson(),d.sourceUrl(),d.officialIndex(),d.termsUrl(),d.brand(),d.region(),d.language(),
            d.acquiredOn(),d.checkedOn(),d.useCondition(),d.distributionAllowed(),d.externalUploadAllowed(),d.localFile(),
            d.provenanceJson(),null,"PENDING",false);
        var row=new NewRow<>(d);assertEquals(1,mapper.insertDocument(row));assertNotNull(row.getId());return row.getId();
    }
    void sqlError(int code,String state,String constraint,Runnable action) {
        RuntimeException failure=assertThrows(RuntimeException.class,action::run);
        SQLException actual=null;
        for(Throwable c=failure;c!=null;c=c.getCause())if(c instanceof SQLException s){actual=s;break;}
        assertNotNull(actual);assertEquals(code,actual.getErrorCode());assertEquals(state,actual.getSQLState());
        if(constraint!=null)assertTrue(actual.getMessage().contains(constraint));
    }
    @Test void migrationContractAndNoAutomaticProductionChunks() {
        assertEquals(List.of("1","2","3","4","5","6","7"),jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunks",Integer.class));
    }
    @Test void officialMetadataRoundTripAndInitialState() {
        Document d=fixture("brother-hll2350dw-oug-d");var row=new NewRow<>(d);mapper.insertDocument(row);
        Document saved=mapper.document(d.documentKey(),d.version());assertEquals(row.getId(),saved.id());
        assertEquals(d.sourceUrl(),saved.sourceUrl());assertEquals(d.officialIndex(),saved.officialIndex());
        assertEquals(d.termsUrl(),saved.termsUrl());assertEquals(d.acquiredOn(),saved.acquiredOn());
        assertEquals(d.useCondition(),saved.useCondition());assertEquals(d.localFile(),saved.localFile());
        assertFalse(saved.selfAuthored());assertFalse(saved.simulated());assertFalse(saved.distributionAllowed());
        assertFalse(saved.externalUploadAllowed());assertEquals("MODELS",saved.modelScope());
        assertTrue(saved.modelsJson().contains("HL-L2350DW"));assertTrue(saved.provenanceJson().contains("sha256"));
        assertNull(saved.contentHash());assertFalse(saved.active());assertEquals("PENDING",saved.status());
        assertTrue(mapper.chunks(saved.id()).isEmpty());
    }
    @Test void simulationRoundTripAndNullOfficialFields() {
        var d=fixture("simulated-repair-workflow");mapper.insertDocument(new NewRow<>(d));
        var saved=mapper.document(d.documentKey(),d.version());
        assertTrue(saved.selfAuthored());assertTrue(saved.simulated());assertEquals("GENERAL",saved.modelScope());
        assertEquals("[]",saved.modelsJson());assertEquals(d.version(),saved.version());assertEquals(d.title(),saved.title());
        assertEquals(d.checkedOn(),saved.checkedOn());assertTrue(saved.distributionAllowed());
        assertNull(saved.sourceUrl());assertNull(saved.officialIndex());assertNull(saved.termsUrl());assertNull(saved.acquiredOn());
        assertNull(saved.brand());assertNull(saved.region());assertNull(saved.language());assertNull(saved.contentHash());
    }
    @Test void chunkMapperUnicodeOrderingAndNullableLocationsAreFixturesOnly() {
        long id=insert("simulated-repair-workflow");
        var later=new NewRow<>(new Chunk(null,id,1,"数据层夹具🖨，不是提取正文",null,null));
        var first=new NewRow<>(new Chunk(null,id,0,"数据层夹具：引号'与换行\n",7,"夹具章节"));
        mapper.insertChunk(later);mapper.insertChunk(first);
        var rows=mapper.chunks(id);assertEquals(2,rows.size());assertEquals(first.getId(),rows.get(0).id());
        assertEquals(first.getRow().content(),rows.get(0).content());assertEquals(7,rows.get(0).pageNumber());
        assertEquals("夹具章节",rows.get(0).section());assertNull(rows.get(1).pageNumber());assertNull(rows.get(1).section());
        assertEquals(later.getRow().content(),rows.get(1).content());assertTrue(mapper.chunks(Long.MAX_VALUE).isEmpty());
    }
    @Test void realForeignKeyRejectsMissingParent() {
        sqlError(1452,"23000","fk_knowledge_chunks_document",()->mapper.insertChunk(new NewRow<>(new Chunk(null,Long.MAX_VALUE,0,"夹具",null,null))));
    }
    @Test void documentVersionUniqueConstraint() {
        var d=fixture("simulated-repair-workflow");mapper.insertDocument(new NewRow<>(d));
        sqlError(1062,"23000","uk_knowledge_document_version",()->mapper.insertDocument(new NewRow<>(d)));
    }
    @Test void chunkIndexUniqueConstraint() {
        long id=insert("simulated-repair-workflow");var c=new Chunk(null,id,0,"夹具",null,null);mapper.insertChunk(new NewRow<>(c));
        sqlError(1062,"23000","uk_knowledge_chunk_index",()->mapper.insertChunk(new NewRow<>(c)));
    }
    @Test void nonnullAndLengthRemainDatabaseBoundaries() {
        long id=insert("simulated-repair-workflow");
        sqlError(1048,"23000",null,()->mapper.insertChunk(new NewRow<>(new Chunk(null,id,0,null,null,null))));
        sqlError(1406,"22001",null,()->mapper.insertChunk(new NewRow<>(new Chunk(null,id,1,"夹具",null,"界".repeat(501)))));
    }
    @Test void checkConstraintsAndRestrictDeletion() {
        long id=insert("simulated-repair-workflow");
        sqlError(3819,"HY000","ck_knowledge_chunk_page",()->mapper.insertChunk(new NewRow<>(new Chunk(null,id,0,"夹具",0,null))));
        sqlError(3819,"HY000","ck_knowledge_active",()->jdbc.update("UPDATE knowledge_documents SET active=true WHERE id=?",id));
        sqlError(3819,"HY000","ck_knowledge_status",()->jdbc.update("UPDATE knowledge_documents SET status='REGISTERED_READY' WHERE id=?",id));
        mapper.insertChunk(new NewRow<>(new Chunk(null,id,0,"夹具",null,null)));
        sqlError(1451,"23000","fk_knowledge_chunks_document",()->jdbc.update("DELETE FROM knowledge_documents WHERE id=?",id));
    }
}

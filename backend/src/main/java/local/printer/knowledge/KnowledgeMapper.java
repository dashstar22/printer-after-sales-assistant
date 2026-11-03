package local.printer.knowledge;

import java.util.List;
import org.apache.ibatis.annotations.*;
import local.printer.knowledge.KnowledgeRows.*;

@Mapper
public interface KnowledgeMapper {
    String COLUMNS = "id,document_key AS documentKey,version,title,source_type AS sourceType,"
        + "self_authored AS selfAuthored,is_simulated AS simulated,model_scope AS modelScope,models_json AS modelsJson,"
        + "source_url AS sourceUrl,official_index AS officialIndex,terms_url AS termsUrl,brand,region,language,"
        + "acquired_on AS acquiredOn,checked_on AS checkedOn,use_condition AS useCondition,"
        + "distribution_allowed AS distributionAllowed,external_upload_allowed AS externalUploadAllowed,"
        + "local_file AS localFile,provenance_json AS provenanceJson,content_hash AS contentHash,status,active";

    @Insert("""
        INSERT INTO knowledge_documents(document_key,version,title,source_type,self_authored,is_simulated,
          model_scope,models_json,source_url,official_index,terms_url,brand,region,language,acquired_on,
          checked_on,use_condition,distribution_allowed,external_upload_allowed,local_file,provenance_json)
        VALUES(#{row.documentKey},#{row.version},#{row.title},#{row.sourceType},#{row.selfAuthored},#{row.simulated},
          #{row.modelScope},#{row.modelsJson},#{row.sourceUrl},#{row.officialIndex},#{row.termsUrl},#{row.brand},
          #{row.region},#{row.language},#{row.acquiredOn},#{row.checkedOn},#{row.useCondition},
          #{row.distributionAllowed},#{row.externalUploadAllowed},#{row.localFile},#{row.provenanceJson})
        """)
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertDocument(NewRow<Document> row);

    @Select("SELECT " + COLUMNS + " FROM knowledge_documents WHERE document_key=#{key} AND version=#{version}")
    Document document(@Param("key") String key, @Param("version") String version);

    @Select("SELECT " + COLUMNS + " FROM knowledge_documents WHERE id=#{id}")
    Document documentById(long id);

    @Select("SELECT " + COLUMNS + " FROM knowledge_documents WHERE document_key=#{key} AND status='READY' AND active=true ORDER BY id DESC LIMIT 2")
    List<Document> activeDocuments(String key);

    @Select("SELECT " + COLUMNS + " FROM knowledge_documents WHERE id=#{id} FOR UPDATE")
    Document lockDocument(long id);

    String READ_REVISION="COALESCE((SELECT g.revision FROM knowledge_active_generations a JOIN knowledge_index_generations g ON g.generation_id=a.generation_id WHERE g.document_id=#{id}),1)";
    @Select("SELECT COUNT(*) FROM knowledge_chunks WHERE document_id=#{id} AND revision="+READ_REVISION)
    int chunkCount(long id);

    @Insert("INSERT INTO knowledge_chunks(document_id,chunk_index,content,page_number,section) "
        + "VALUES(#{row.documentId},#{row.chunkIndex},#{row.content},#{row.pageNumber},#{row.section})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertChunk(NewRow<Chunk> row);

    @Select("SELECT id,document_id AS documentId,chunk_index AS chunkIndex,content,page_number AS pageNumber,section "
        + "FROM knowledge_chunks WHERE document_id=#{id} AND revision="+READ_REVISION+" ORDER BY chunk_index")
    List<Chunk> chunks(long id);
}

package local.printer.knowledge;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
import local.printer.knowledge.KnowledgeRows.Document;

@Mapper
public interface KnowledgeProcessingMapper {
    record Processing(long documentId,String layoutHash,String metadataHash,String model,int dimensions,String collectionName,
        int chunkSize,int chunkOverlap,long attempt,String failureCode,LocalDateTime publishedAt){}
    @Select("SELECT document_id AS documentId,layout_hash AS layoutHash,metadata_hash AS metadataHash,model,dimensions,collection_name AS collectionName,"
        +"chunk_size AS chunkSize,chunk_overlap AS chunkOverlap,attempt,failure_code AS failureCode,published_at AS publishedAt FROM knowledge_processing WHERE document_id=#{id}")
    Processing processing(long id);
    @Insert("INSERT INTO knowledge_processing(document_id,layout_hash,metadata_hash,model,dimensions,collection_name,chunk_size,chunk_overlap) "
        +"VALUES(#{documentId},#{layoutHash},#{metadataHash},#{model},#{dimensions},#{collectionName},#{chunkSize},#{chunkOverlap})")
    int insert(Processing p);
    @Update("UPDATE knowledge_processing SET attempt=attempt+1,failure_code=NULL WHERE document_id=#{id} AND attempt<9223372036854775807")
    int retry(long id);
    @Update("UPDATE knowledge_documents SET content_hash=#{hash},status='PROCESSING' WHERE id=#{id} AND active=FALSE AND status IN ('PENDING','PROCESSING','FAILED')")
    int start(@Param("id")long id,@Param("hash")String hash);
    @Select("SELECT "+KnowledgeMapper.COLUMNS+" FROM knowledge_documents WHERE document_key=#{key} ORDER BY id FOR UPDATE")
    List<Document> lockSeries(String key);
    @Update("UPDATE knowledge_documents SET active=FALSE WHERE document_key=#{key} AND active=TRUE")
    int retire(String key);
    @Update("UPDATE knowledge_documents SET status='READY',active=TRUE WHERE id=#{id} AND status='PROCESSING'")
    int ready(long id);
    @Update("UPDATE knowledge_processing SET published_at=UTC_TIMESTAMP(6),failure_code=NULL WHERE document_id=#{id} AND attempt=#{attempt}")
    int published(@Param("id")long id,@Param("attempt")long attempt);
    @Update("UPDATE knowledge_processing p JOIN knowledge_documents d ON d.id=p.document_id SET p.failure_code=#{code},d.status='FAILED' "
        +"WHERE p.document_id=#{id} AND p.attempt=#{attempt} AND d.status='PROCESSING' AND d.active=FALSE")
    int failed(@Param("id")long id,@Param("attempt")long attempt,@Param("code")String code);
}

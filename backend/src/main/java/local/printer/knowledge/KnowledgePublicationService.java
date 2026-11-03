package local.printer.knowledge;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import local.printer.knowledge.KnowledgeRows.*;
import local.printer.knowledge.KnowledgeProcessingMapper.Processing;

@Service
@ConditionalOnNotWebApplication
@ConditionalOnProperty(name="app.knowledge.maintenance-enabled",havingValue="true")
public class KnowledgePublicationService {
    @org.springframework.beans.factory.annotation.Autowired
    private local.printer.operations.ExternalCallBudget externalBudget;
    private final KnowledgeMapper mapper;private final KnowledgeProcessingMapper processing;private final DataSource dataSource;
    private final TransactionTemplate tx;private final Environment env;
    public KnowledgePublicationService(KnowledgeMapper mapper,KnowledgeProcessingMapper processing,DataSource ds,PlatformTransactionManager tm,Environment env){
        this.mapper=mapper;this.processing=processing;dataSource=ds;tx=new TransactionTemplate(tm);this.env=env;
    }
    public record Result(int chunks,int embedded,boolean alreadyPublished,boolean active){}
    public Result publish(Path root,long id,int size,int overlap){
        try(var scope=local.printer.operations.ExternalCallBudget.scope("legacy:"+id,"TASK",120000)){return execute(root,id,size,overlap);}catch(KnowledgeFailure failure){throw failure;}
        catch(Exception failure){throw new KnowledgeFailure("KNOWLEDGE_DATABASE_ERROR",3);}
    }
    private Result execute(Path root,long id,int size,int overlap)throws Exception{
        KnowledgeTextExtractor.parameters(size,overlap);Document initial=mapper.documentById(id);
        if(initial==null)throw KnowledgeFailure.invalid("KNOWLEDGE_DOCUMENT_NOT_FOUND");
        if(!initial.externalUploadAllowed())throw KnowledgeFailure.invalid("KNOWLEDGE_UPLOAD_FORBIDDEN");
        try(var connection=dataSource.getConnection()){
            String database=connection.getCatalog();String lock=KnowledgeIdentity.digest(database,initial.documentKey());
            if(scalar(connection,"SELECT GET_LOCK(?,0)",lock)!=1)throw conflict("PROCESSING_BUSY");
            try{return locked(root,initial,size,overlap,database,connection,lock);}
            finally{scalar(connection,"SELECT RELEASE_LOCK(?)",lock);}
        }
    }
    private Result locked(Path root,Document initial,int size,int overlap,String database,Connection lockConnection,String lock)throws Exception{
        Document source=mapper.documentById(initial.id());if(!initial.equals(source))throw conflict("PROCESSING_CONFLICT");
        var chunks=mapper.chunks(source.id());if(chunks.isEmpty())throw conflict("EXTRACTION_REQUIRED");
        if(chunks.size()>KnowledgeTextExtractor.MAX_CHUNKS)throw KnowledgeFailure.invalid("KNOWLEDGE_LIMIT_EXCEEDED");
        var fresh=KnowledgeTextExtractor.extract(root,source.localFile(),size,overlap);
        if(fresh.parts().size()!=chunks.size())throw conflict("SOURCE_CHANGED");
        for(int i=0;i<chunks.size();i++){
            var a=chunks.get(i);var b=fresh.parts().get(i);
            if(a.chunkIndex()!=i||!a.content().equals(b.text())||!Objects.equals(a.pageNumber(),b.page())||!Objects.equals(a.section(),b.section()))throw conflict("SOURCE_CHANGED");
        }
        String hash=fresh.sourceHash(),metadata=KnowledgeIdentity.metadata(source),layout=KnowledgeIdentity.layout(source,chunks,size,overlap);
        if(source.contentHash()!=null&&!source.contentHash().equals(hash))throw conflict("SOURCE_CHANGED");
        var client=new KnowledgeVectorClient(env,externalBudget);Processing old=processing.processing(source.id());
        if(old!=null&&(!old.layoutHash().equals(layout)||!old.metadataHash().equals(metadata)||!old.model().equals(client.model)
            ||old.dimensions()!=client.dimensions||!old.collectionName().equals(client.collection)||old.chunkSize()!=size||old.chunkOverlap()!=overlap))throw conflict("PROCESSING_CONFLICT");
        boolean ready=source.status().equals("READY");
        if(ready&&(old==null||old.publishedAt()==null)||!ready&&source.active())throw conflict("PROCESSING_CONFLICT");
        // Scope is a version-family property. Locking transactions below also recheck it.
        tx.executeWithoutResult(s->{for(var d:processing.lockSeries(source.documentKey()))if(!KnowledgeIdentity.scope(d).equals(KnowledgeIdentity.scope(source)))throw conflict("PROCESSING_CONFLICT");});
        if(ready){client.writeAndVerify(database,source,chunks,layout,true);return new Result(chunks.size(),0,true,source.active());}
        assertLock(lockConnection,lock);
        long attempt=tx.execute(s->{
            if(!source.equals(mapper.lockDocument(source.id())))throw conflict("PROCESSING_CONFLICT");
            if(old==null){if(source.contentHash()!=null)throw conflict("PROCESSING_CONFLICT");
                processing.insert(new Processing(source.id(),layout,metadata,client.model,client.dimensions,client.collection,size,overlap,1,null,null));}
            else if(processing.retry(source.id())!=1)throw conflict("PROCESSING_CONFLICT");
            if(processing.start(source.id(),hash)!=1)throw conflict("PROCESSING_CONFLICT");return processing.processing(source.id()).attempt();
        });
        try{
            Document working=mapper.documentById(source.id());int embedded=client.writeAndVerify(database,working,chunks,layout,false);
            assertLock(lockConnection,lock);
            tx.executeWithoutResult(s->{
                var series=processing.lockSeries(source.documentKey());var current=mapper.documentById(source.id());var binding=processing.processing(source.id());
                if(binding.attempt()!=attempt||!current.status().equals("PROCESSING")||!Objects.equals(current.contentHash(),hash)
                    ||!KnowledgeIdentity.metadata(current).equals(metadata)||!KnowledgeIdentity.layout(current,mapper.chunks(source.id()),size,overlap).equals(layout))throw conflict("PROCESSING_CONFLICT");
                for(var d:series)if(!KnowledgeIdentity.scope(d).equals(KnowledgeIdentity.scope(current)))throw conflict("PROCESSING_CONFLICT");
                processing.retire(source.documentKey());if(processing.ready(source.id())!=1||processing.published(source.id(),attempt)!=1)throw conflict("PROCESSING_CONFLICT");
            });return new Result(chunks.size(),embedded,false,true);
        }catch(RuntimeException failure){
            String code=failure instanceof KnowledgeFailure?failure.getMessage():"KNOWLEDGE_DATABASE_ERROR";
            try{tx.executeWithoutResult(s->processing.failed(source.id(),attempt,code));}catch(RuntimeException ignored){throw new KnowledgeFailure("KNOWLEDGE_DATABASE_ERROR",3);}
            if(failure instanceof KnowledgeFailure known)throw known;throw new KnowledgeFailure("KNOWLEDGE_DATABASE_ERROR",3);
        }
    }
    private static int scalar(Connection c,String query,String lock)throws SQLException{
        try(var statement=c.prepareStatement(query)){statement.setString(1,lock);try(var rows=statement.executeQuery()){rows.next();return rows.getInt(1);}}
    }
    private static void assertLock(Connection c,String lock)throws SQLException{
        if(scalar(c,"SELECT IS_USED_LOCK(?)=CONNECTION_ID()",lock)!=1)throw conflict("PROCESSING_BUSY");
    }
    private static KnowledgeFailure conflict(String code){return new KnowledgeFailure("KNOWLEDGE_"+code,4);}
}

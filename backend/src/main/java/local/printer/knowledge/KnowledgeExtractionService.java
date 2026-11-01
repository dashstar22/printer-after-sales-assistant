package local.printer.knowledge;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import local.printer.knowledge.KnowledgeRows.*;

@Service
@ConditionalOnNotWebApplication
@ConditionalOnProperty(name="app.knowledge.maintenance-enabled",havingValue="true")
public class KnowledgeExtractionService {
    private final KnowledgeMapper mapper;private final TransactionTemplate tx;
    public KnowledgeExtractionService(KnowledgeMapper mapper,PlatformTransactionManager manager){this.mapper=mapper;this.tx=new TransactionTemplate(manager);}
    public record Result(int chunks,int units,int emptyPages,int chunkSize,int overlap){}
    public Result extract(Path root,long id,int size,int overlap){
        KnowledgeTextExtractor.parameters(size,overlap);
        Document source=mapper.documentById(id);
        if(source==null)throw KnowledgeFailure.invalid("KNOWLEDGE_DOCUMENT_NOT_FOUND");
        if(!pending(source)||mapper.chunkCount(id)>0)throw conflict();
        var extracted=KnowledgeTextExtractor.extract(root,source.localFile(),size,overlap);
        try{
            tx.executeWithoutResult(status->{
                Document locked=mapper.lockDocument(id);
                if(!Objects.equals(source,locked)||!pending(locked)||mapper.chunkCount(id)>0)throw conflict();
                int index=0;
                for(var part:extracted.parts()){
                    var row=new NewRow<>(new Chunk(null,id,index++,part.text(),part.page(),part.section()));
                    if(mapper.insertChunk(row)!=1)throw new KnowledgeFailure("KNOWLEDGE_DATABASE_ERROR",3);
                }
            });
            return new Result(extracted.parts().size(),extracted.units(),extracted.emptyPages(),size,overlap);
        }catch(KnowledgeFailure failure){throw failure;
        }catch(RuntimeException failure){throw new KnowledgeFailure("KNOWLEDGE_DATABASE_ERROR",3);}
    }
    private static boolean pending(Document d){return d!=null && d.status().equals("PENDING")&&!d.active()&&d.contentHash()==null;}
    private static KnowledgeFailure conflict(){return new KnowledgeFailure("KNOWLEDGE_EXTRACTION_CONFLICT",4);}
}

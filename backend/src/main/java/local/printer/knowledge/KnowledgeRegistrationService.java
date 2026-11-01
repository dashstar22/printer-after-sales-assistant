package local.printer.knowledge;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import local.printer.knowledge.KnowledgeRows.NewRow;

@Service
@ConditionalOnNotWebApplication
@ConditionalOnProperty(name="app.knowledge.maintenance-enabled",havingValue="true")
public class KnowledgeRegistrationService {
    private final KnowledgeMapper mapper;
    private final TransactionTemplate tx;
    public KnowledgeRegistrationService(KnowledgeMapper mapper, PlatformTransactionManager manager) {
        this.mapper=mapper;this.tx=new TransactionTemplate(manager);
    }
    public int register(Path root,String manifest,List<String> ids) {
        var documents=KnowledgeManifest.read(root,manifest,ids);
        try {
            tx.executeWithoutResult(status->{
                for(var document:documents) {
                    if(mapper.insertDocument(new NewRow<>(document))!=1) throw new KnowledgeFailure("KNOWLEDGE_DATABASE_ERROR",3);
                }
            });
            return documents.size();
        } catch(DuplicateKeyException failure) { throw new KnowledgeFailure("KNOWLEDGE_DUPLICATE",4);
        } catch(KnowledgeFailure failure) {throw failure;
        } catch(RuntimeException failure) {throw new KnowledgeFailure("KNOWLEDGE_DATABASE_ERROR",3);}
    }
}

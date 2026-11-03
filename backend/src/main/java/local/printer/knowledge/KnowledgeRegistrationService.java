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
    private final KnowledgeApplicationService application;
    public KnowledgeRegistrationService(KnowledgeApplicationService application) { this.application=application; }
    public int register(Path root,String manifest,List<String> ids) {
        return application.register(KnowledgeManifest.read(root,manifest,ids)).size();
    }
}

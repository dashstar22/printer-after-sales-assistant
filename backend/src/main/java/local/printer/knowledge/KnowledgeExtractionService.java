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
    private final KnowledgeApplicationService application;
    public KnowledgeExtractionService(KnowledgeApplicationService application){this.application=application;}
    public record Result(int chunks,int units,int emptyPages,int chunkSize,int overlap){}
    public Result extract(Path root,long id,int size,int overlap){
        var r=application.extract(root,id,size,overlap);
        return new Result(r.chunks(),r.units(),r.emptyPages(),r.chunkSize(),r.overlap());
    }
}

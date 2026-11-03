package local.printer.jobs;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import local.printer.knowledge.index.KnowledgeIndexPipeline;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** One in-process consumer. Results/failures commit before ACK or terminal rejection. */
@Component
@ConditionalOnProperty(name="app.knowledge.jobs.enabled",havingValue="true")
public class KnowledgeJobWorker {
    private final KnowledgeJobStore store;
    private final KnowledgeIndexPipeline pipeline;
    private final int leaseSeconds;
    private final String owner=UUID.randomUUID().toString();
    private final JsonMapper json=JsonMapper.builder().build();
    public KnowledgeJobWorker(KnowledgeJobStore store,KnowledgeIndexPipeline pipeline,
            @Value("${app.knowledge.jobs.lease-seconds:120}") int leaseSeconds) {
        if(leaseSeconds<10 || leaseSeconds>3600)throw new IllegalArgumentException("Invalid knowledge job lease");
        this.store=store;this.pipeline=pipeline;this.leaseSeconds=leaseSeconds;
    }
    public void consume(Message message,Channel channel) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();
        String jobId;int revision;
        try {
            if(message.getBody().length>512)throw new IllegalArgumentException();
            var node=json.readTree(message.getBody());
            if(!node.isObject() || !new HashSet<>(node.propertyNames()).equals(Set.of("jobId","revision","stage"))
                    || !node.path("jobId").isString() || !node.path("revision").isIntegralNumber()
                    || !node.path("stage").isString() || node.path("stage").asString().length()>40)throw new IllegalArgumentException();
            jobId=UUID.fromString(node.path("jobId").asString()).toString();revision=node.path("revision").asInt();
            if(revision<1)throw new IllegalArgumentException();
        }catch(RuntimeException invalid) {channel.basicReject(tag,false);return;}
        KnowledgeJobStore.Claim claim;
        try {
            var acquired=store.claim(jobId,owner,leaseSeconds);
            if(acquired.isEmpty()){channel.basicAck(tag,false);return;}
            claim=acquired.get();
            if(claim.revision()!=revision) {
                store.fail(claim,"MESSAGE_REVISION_MISMATCH",true);channel.basicReject(tag,false);return;
            }
        }catch(RuntimeException databaseUnavailable) {channel.basicNack(tag,false,true);return;}
        try {
            pipeline.process(claim.jobId(),claim.documentId(),claim.revision(),()->store.heartbeat(claim,leaseSeconds));
            store.complete(claim);channel.basicAck(tag,false);
        }catch(local.printer.operations.ExternalCallBudget.Limit failure) {
            finishFailure(claim,"EXTERNAL_BUDGET_EXHAUSTED",true,channel,tag);
        }catch(KnowledgeIndexPipeline.IndexFailure failure) {
            finishFailure(claim,failure.unknownCost()?"EXTERNAL_RESULT_UNKNOWN":failure.code(),
                    failure.permanent() || failure.unknownCost(),channel,tag);
        }catch(RuntimeException failure) {finishFailure(claim,"WORKER_STAGE_FAILED",false,channel,tag);}
    }
    private void finishFailure(KnowledgeJobStore.Claim claim,String code,boolean permanent,Channel channel,long tag) throws IOException {
        try {
            String state=store.fail(claim,code,permanent);
            if("FAILED".equals(state)) channel.basicReject(tag,false);
            else channel.basicAck(tag,false);
        }catch(RuntimeException persistenceUnavailable) {channel.basicNack(tag,false,true);}
    }
}

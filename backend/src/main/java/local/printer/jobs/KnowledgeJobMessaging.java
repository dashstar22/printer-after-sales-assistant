package local.printer.jobs;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

/** MySQL remains authoritative; a lost confirm only causes a safe duplicate delivery. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="app.knowledge.jobs.enabled",havingValue="true")
public class KnowledgeJobMessaging {
    private final KnowledgeJobStore store;
    private final RabbitTemplate rabbit;
    private final String exchange,routingKey;
    private final JsonMapper json=JsonMapper.builder().build();

    public KnowledgeJobMessaging(KnowledgeJobStore store,ConnectionFactory connection,
            @Value("${app.knowledge.jobs.exchange:knowledge.jobs}") String exchange,
            @Value("${app.knowledge.jobs.routing-key:knowledge.publish}") String routingKey) {
        if(!(connection instanceof CachingConnectionFactory cached))
            throw new IllegalStateException("Knowledge jobs require correlated publisher confirms");
        cached.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        cached.setPublisherReturns(true);
        this.store=store;this.exchange=exchange;this.routingKey=routingKey;
        rabbit=new RabbitTemplate(connection);rabbit.setMandatory(true);
    }

    @Bean RabbitAdmin knowledgeJobRabbitAdmin(ConnectionFactory connection) {return new RabbitAdmin(connection);}

    @Bean Declarables knowledgeJobTopology(
            @Value("${app.knowledge.jobs.queue:knowledge.jobs.work}") String queue,
            @Value("${app.knowledge.jobs.dead-exchange:knowledge.jobs.dead}") String deadExchange,
            @Value("${app.knowledge.jobs.dead-queue:knowledge.jobs.dead}") String deadQueue) {
        var work=QueueBuilder.durable(queue).deadLetterExchange(deadExchange).deadLetterRoutingKey("failed").build();
        var dead=QueueBuilder.durable(deadQueue).build();
        var liveExchange=new DirectExchange(exchange,true,false);
        var dlx=new DirectExchange(deadExchange,true,false);
        return new Declarables(liveExchange,dlx,work,dead,
                BindingBuilder.bind(work).to(liveExchange).with(routingKey),
                BindingBuilder.bind(dead).to(dlx).with("failed"));
    }

    @Bean SimpleMessageListenerContainer knowledgeJobListener(ConnectionFactory connection,KnowledgeJobWorker worker,
            @Value("${app.knowledge.jobs.queue:knowledge.jobs.work}") String queue) {
        var container=new SimpleMessageListenerContainer(connection);
        container.setQueueNames(queue);container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(1);container.setMaxConcurrentConsumers(1);container.setPrefetchCount(1);
        container.setDefaultRequeueRejected(false);
        container.setMessageListener((org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener)worker::consume);
        return container;
    }

    @Scheduled(fixedDelayString="${app.knowledge.jobs.dispatch-delay-ms:1000}")
    public void dispatch() {
        // Both scans are idempotent; a restart between the SQL commit and send loses no intention.
        try {store.reconcileRequests();store.recoverExpired(100);}
        catch(RuntimeException unavailable) {return;}
        for(var row:store.outboxDue(20)) {
            long id=((Number)row.get("id")).longValue();
            String payload=json.createObjectNode().put("jobId",(String)row.get("job_id"))
                    .put("revision",((Number)row.get("revision")).intValue()).put("stage",(String)row.get("stage")).toString();
            try {
                var properties=new MessageProperties();properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);properties.setContentEncoding("UTF-8");
                properties.setMessageId(Long.toString(id));
                var correlation=new CorrelationData(id+":"+UUID.randomUUID());
                rabbit.send(exchange,routingKey,new Message(payload.getBytes(StandardCharsets.UTF_8),properties),correlation);
                var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
                if(confirm.isAck() && correlation.getReturned()==null) store.outboxConfirmed(id);
                else store.outboxFailed(id,correlation.getReturned()!=null?"BROKER_UNROUTABLE":"BROKER_NACK");
            }catch(InterruptedException stopped) {
                Thread.currentThread().interrupt();store.outboxFailed(id,"BROKER_CONFIRM_UNKNOWN");return;
            }catch(Exception unavailable) {store.outboxFailed(id,"BROKER_CONFIRM_UNKNOWN");}
        }
    }
}

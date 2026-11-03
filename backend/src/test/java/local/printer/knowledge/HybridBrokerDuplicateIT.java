package local.printer.knowledge;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Opt-in transport helper: send an actual confirmed duplicate to the isolated broker. */
class HybridBrokerDuplicateIT {
    @Test void publishConfirmedDuplicate() throws Exception {
        var env = System.getenv();
        if (!env.containsKey("PHASE4_DUPLICATE_JOB")) return;
        var factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(Integer.parseInt(env.get("RABBITMQ_PORT")));
        factory.setUsername(env.get("RABBITMQ_USER"));
        factory.setPassword(env.get("RABBITMQ_PASSWORD"));
        factory.setAutomaticRecoveryEnabled(false);
        try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
            channel.confirmSelect();
            var returned = new java.util.concurrent.atomic.AtomicBoolean();
            channel.addReturnListener(message -> returned.set(true));
            var payload = "{\"jobId\":\"" + java.util.UUID.fromString(env.get("PHASE4_DUPLICATE_JOB"))
                    + "\",\"revision\":1,\"stage\":\"EMBEDDING\"}";
            channel.basicPublish(env.get("KNOWLEDGE_JOBS_EXCHANGE"), "publish", true,
                    new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build(),
                    payload.getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(5000);
            org.junit.jupiter.api.Assertions.assertFalse(returned.get(), "Duplicate must route to the work queue");
        }
    }
}

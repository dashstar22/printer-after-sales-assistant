package local.printer.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import local.printer.knowledge.index.KnowledgeIndexPipeline;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Real isolated broker; store/pipeline are explicitly controlled fixtures, not SQL evidence. */
class KnowledgeJobBrokerIT {
    private static String env(String key){String value=System.getenv(key);assertNotNull(value,key);return value;}
    private static void docker(String command,String name) throws Exception {
        assertTrue(name.matches("phase3-broker-[a-z0-9]+"));
        var process=new ProcessBuilder("docker",command,name).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();assertTrue(process.waitFor(60,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
    }
    private static void awaitBroker(CachingConnectionFactory connection) throws Exception {
        Exception last=null;
        for(int i=0;i<60;i++)try{connection.createConnection().createChannel(false).close();return;}catch(Exception e){last=e;Thread.sleep(500);}
        throw new AssertionError("isolated broker unavailable",last);
    }
    private static Message message(String id) {
        var props=new MessageProperties();props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setContentType("application/json");
        return new Message(("{\"jobId\":\""+id+"\",\"revision\":1,\"stage\":\"INDEXING\"}").getBytes(StandardCharsets.UTF_8),props);
    }
    @Test void confirmReturnDisconnectRestartRedeliveryAndManualAck() throws Exception {
        String container=env("PHASE3_RABBIT_CONTAINER"),prefix="p3."+UUID.randomUUID();
        String exchange=prefix+".exchange",queue=prefix+".work",deadExchange=prefix+".dlx",deadQueue=prefix+".dead";
        var connection=new CachingConnectionFactory("127.0.0.1",Integer.parseInt(env("PHASE3_RABBIT_PORT")));
        connection.setUsername(env("PHASE3_RABBIT_USER"));connection.setPassword(env("PHASE3_RABBIT_PASSWORD"));
        connection.setConnectionTimeout(1000);
        var store=mock(KnowledgeJobStore.class);var pipeline=mock(KnowledgeIndexPipeline.class);
        var dispatch=new KnowledgeJobMessaging(store,connection,exchange,"publish");
        var admin=new RabbitAdmin(connection);var topology=dispatch.knowledgeJobTopology(queue,deadExchange,deadQueue);
        for(var item:topology.getDeclarables()) {
            if(item instanceof Exchange ex)admin.declareExchange(ex);
            if(item instanceof org.springframework.amqp.core.Queue q)admin.declareQueue(q);
        }
        for(var item:topology.getDeclarables())if(item instanceof Binding binding)admin.declareBinding(binding);
        var rabbit=new RabbitTemplate(connection);rabbit.setMandatory(true);
        String id=UUID.randomUUID().toString();
        var row=Map.<String,Object>of("id",1L,"job_id",id,"revision",1,"stage","INDEXING");
        when(store.outboxDue(20)).thenReturn(List.of(row));
        dispatch.dispatch();verify(store).outboxConfirmed(1L);
        assertEquals(1,admin.getQueueInfo(queue).getMessageCount());

        // Disconnect after the confirm: the persistent message remains on disk after broker restart.
        connection.destroy();docker("restart",container);awaitBroker(connection);
        assertEquals(1,admin.getQueueInfo(queue).getMessageCount());
        var nativeConnection=connection.createConnection();var channel=nativeConnection.createChannel(false);
        var delivery=channel.basicGet(queue,false);assertNotNull(delivery);
        assertEquals(2,delivery.getProps().getDeliveryMode());
        assertFalse(new String(delivery.getBody(),StandardCharsets.UTF_8).contains("content"));
        // Process exit before ACK: channel close returns the exact message to the durable queue.
        channel.close();connection.destroy();awaitBroker(connection);
        channel=connection.createConnection().createChannel(false);delivery=channel.basicGet(queue,false);
        assertNotNull(delivery);assertTrue(delivery.getEnvelope().isRedeliver());
        var claim=new KnowledgeJobStore.Claim(id,10L,1,0,1,"token","INDEXING","binding");
        when(store.claim(eq(id),anyString(),eq(120))).thenReturn(Optional.of(claim));
        var worker=new KnowledgeJobWorker(store,pipeline,120);
        var props=new MessageProperties();props.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        worker.consume(new Message(delivery.getBody(),props),channel);
        InOrder order=inOrder(pipeline,store);order.verify(store).claim(eq(id),anyString(),eq(120));
        order.verify(pipeline).process(eq(id),eq(10L),eq(1),any());order.verify(store).complete(claim);
        assertNull(channel.basicGet(queue,true));channel.close();

        // A duplicate wake-up for an already completed job is ACKed without executing a stage.
        when(store.claim(eq(id),anyString(),eq(120))).thenReturn(Optional.empty());
        rabbit.send(exchange,"publish",message(id));
        channel=connection.createConnection().createChannel(false);delivery=channel.basicGet(queue,false);assertNotNull(delivery);
        props=new MessageProperties();props.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        worker.consume(new Message(delivery.getBody(),props),channel);channel.close();
        verify(pipeline,times(1)).process(eq(id),eq(10L),eq(1),any());

        // Permanent failure is persisted then rejected into the broker's durable dead-letter queue.
        when(store.claim(eq(id),anyString(),eq(120))).thenReturn(Optional.of(claim));
        doThrow(new KnowledgeIndexPipeline.IndexFailure("PURPOSE_NOT_ALLOWED",true,false)).when(pipeline).process(eq(id),eq(10L),eq(1),any());
        when(store.fail(claim,"PURPOSE_NOT_ALLOWED",true)).thenReturn("FAILED");
        rabbit.send(exchange,"publish",message(id));
        channel=connection.createConnection().createChannel(false);delivery=channel.basicGet(queue,false);assertNotNull(delivery);
        props=new MessageProperties();props.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        worker.consume(new Message(delivery.getBody(),props),channel);channel.close();
        assertNotNull(rabbit.receive(deadQueue,5000));verify(store).fail(claim,"PURPOSE_NOT_ALLOWED",true);

        // Exchange ACK alone is insufficient when mandatory routing returns the message.
        admin.removeBinding(new Binding(queue,Binding.DestinationType.QUEUE,exchange,"publish",null));
        clearInvocations(store);dispatch.dispatch();verify(store).outboxFailed(1L,"BROKER_UNROUTABLE");verify(store,never()).outboxConfirmed(anyLong());
        admin.declareBinding(new Binding(queue,Binding.DestinationType.QUEUE,exchange,"publish",null));

        // No confirmation during disconnect must leave the durable SQL intention retryable.
        docker("stop",container);connection.destroy();clearInvocations(store);
        dispatch.dispatch();verify(store).outboxFailed(1L,"BROKER_CONFIRM_UNKNOWN");verify(store,never()).outboxConfirmed(anyLong());
        docker("start",container);awaitBroker(connection);clearInvocations(store);
        dispatch.dispatch();verify(store).outboxConfirmed(1L);assertNotNull(rabbit.receive(queue,5000));
        admin.deleteQueue(queue);admin.deleteQueue(deadQueue);admin.deleteExchange(exchange);admin.deleteExchange(deadExchange);connection.destroy();
    }
}

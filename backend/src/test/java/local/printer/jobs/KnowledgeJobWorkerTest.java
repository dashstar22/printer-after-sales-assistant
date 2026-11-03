package local.printer.jobs;

import static org.mockito.Mockito.*;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import local.printer.knowledge.index.KnowledgeIndexPipeline;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;

class KnowledgeJobWorkerTest {
    private final String id=UUID.randomUUID().toString();
    private final KnowledgeJobStore store=mock(KnowledgeJobStore.class);
    private final KnowledgeIndexPipeline pipeline=mock(KnowledgeIndexPipeline.class);
    private final Channel channel=mock(Channel.class);
    private final KnowledgeJobStore.Claim claim=new KnowledgeJobStore.Claim(id,12,1,0,1,"token","INDEXING","hash");
    private final KnowledgeJobWorker worker=new KnowledgeJobWorker(store,pipeline,120);
    private Message message(String body){var properties=new MessageProperties();properties.setDeliveryTag(19);return new Message(body.getBytes(StandardCharsets.UTF_8),properties);}
    private Message valid(){return message("{\"jobId\":\""+id+"\",\"revision\":1,\"stage\":\"INDEXING\"}");}
    private void acquire(){when(store.claim(eq(id),anyString(),eq(120))).thenReturn(Optional.of(claim));}
    @Test void commitBeforeAck() throws Exception {
        acquire();worker.consume(valid(),channel);
        var order=inOrder(store,pipeline,channel);order.verify(store).claim(eq(id),anyString(),eq(120));
        order.verify(pipeline).process(eq(id),eq(12L),eq(1),any());order.verify(store).complete(claim);order.verify(channel).basicAck(19,false);
    }
    @Test void transientFailureCommitsDueRetryBeforeAck() throws Exception {
        acquire();doThrow(new KnowledgeIndexPipeline.IndexFailure("VECTOR_TEMPORARY",false,false)).when(pipeline).process(eq(id),eq(12L),eq(1),any());
        when(store.fail(claim,"VECTOR_TEMPORARY",false)).thenReturn("RETRY_WAIT");worker.consume(valid(),channel);
        var order=inOrder(store,channel);order.verify(store).fail(claim,"VECTOR_TEMPORARY",false);order.verify(channel).basicAck(19,false);
    }
    @Test void unknownExternalResultStopsAutomaticRetryBeforeDeadLetter() throws Exception {
        acquire();doThrow(new KnowledgeIndexPipeline.IndexFailure("EMBEDDING_TIMEOUT",false,true)).when(pipeline).process(eq(id),eq(12L),eq(1),any());
        when(store.fail(claim,"EXTERNAL_RESULT_UNKNOWN",true)).thenReturn("FAILED");worker.consume(valid(),channel);
        var order=inOrder(store,channel);order.verify(store).fail(claim,"EXTERNAL_RESULT_UNKNOWN",true);order.verify(channel).basicReject(19,false);
    }
    @Test void uncommittedFailureIsNackedAndRequeued() throws Exception {
        acquire();doThrow(new RuntimeException()).when(pipeline).process(eq(id),eq(12L),eq(1),any());
        when(store.fail(claim,"WORKER_STAGE_FAILED",false)).thenThrow(new RuntimeException());worker.consume(valid(),channel);
        verify(channel).basicNack(19,false,true);verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void forgedPayloadIsRejectedWithoutReadingBodyOrClaim() throws Exception {
        worker.consume(message("{\"jobId\":\""+id+"\",\"revision\":1,\"stage\":\"INDEXING\",\"content\":\"secret\"}"),channel);
        verify(channel).basicReject(19,false);verifyNoInteractions(store,pipeline);
    }
}

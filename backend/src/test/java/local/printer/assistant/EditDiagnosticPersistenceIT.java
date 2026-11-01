package local.printer.assistant;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.*;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Only diagnostic* methods are selected by the local runner; inherited business fixtures are unchanged. */
class EditDiagnosticPersistenceIT extends AssistantRepairIT {
    @MockitoSpyBean ConversationMapper observedMapper;
    @org.springframework.beans.factory.annotation.Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    static Path proof;

    @DynamicPropertySource static void diagnosticProperties(DynamicPropertyRegistry properties) throws Exception {
        String configured=System.getenv("T031_EC_F01_PROOF_DIR");
        assertNotNull(configured);proof=Path.of(configured).toAbsolutePath().normalize();
        assertTrue(proof.toString().contains("t031-ec-rev-f01"));Files.createDirectories(proof);
        properties.add("app.assistant.observation-evidence-dir",()->proof.toString());
    }

    JsonNode host(String turn) throws Exception {
        try(var paths=Files.list(proof.resolve("edit"))){
            var rows=paths.filter(p->p.getFileName().toString().startsWith(turn+"-")).sorted().toList();
            assertFalse(rows.isEmpty());
            for(var p:rows){var row=json.readTree(Files.readString(p));if(row.path("phase").asString().equals("HOST_OUTCOME"))return row;}
        }
        throw new AssertionError("HOST_OUTCOME missing");
    }
    void saveCase(String name,String turn,JsonNode reply,JsonNode original,JsonNode current,int modelCalls,boolean replayed) throws Exception {
        Files.writeString(proof.resolve(name+".json"),json.writeValueAsString(Map.of(
            "case",name,"turnId",turn,"reply",reply,"originalDraft",original,"currentDraft",current,
            "hostOutcome",host(turn),"modelCalls",modelCalls,"replayedWithoutNewCalls",replayed,
            "mode","REAL_HTTP_MYSQL_LOOPBACK_MODEL_SIMULATION")),StandardOpenOption.CREATE_NEW);
    }
    void assertSaved(JsonNode outcome,boolean replyPersisted) {
        assertTrue(outcome.path("draftSaved").asBoolean());assertEquals("SAVED",outcome.path("draftPersistence").asString());
        assertEquals(replyPersisted,outcome.path("replyPersisted").asBoolean());
    }

    @Test void diagnosticCommittedEditWithLostAcknowledgementUsesPersistedCheckpoint() throws Exception {
        var browser=customerA();var id=create(browser,orderA);var original=prepare(browser,id);
        String turn=UUID.randomUUID().toString();long before=draftCount();var armed=new AtomicBoolean(true);
        var manager=(ConfigurableTransactionManager)transactionManager;var previous=new ArrayList<>(manager.getTransactionExecutionListeners());
        manager.addListener(new TransactionExecutionListener(){
            @Override public void afterCommit(TransactionExecution transaction,Throwable failure){
                if(failure==null && transaction.isNewTransaction() && AssistantExecution.current()!=null && armed.compareAndSet(true,false))
                    throw new TransactionSystemException("LOCAL_AFTER_COMMIT_ACK_LOSS");
            }
        });
        try {
            plan(call("prepareRepairDraft",Map.of("symptom","新观察")),workflow());
            var result=submit(browser,id,turn,"把故障描述改成新观察",201);var reply=result.path("assistantMessage").path("response");
            assertFalse(armed.get());assertEquals("WRITE_RESULT_UNKNOWN",reply.path("status").asString());
            assertEquals(original.path("id"),reply.path("draft").path("id"));assertEquals(1,reply.path("draft").path("version").asLong());
            assertEquals(current(browser,original),reply.path("draft"));assertEquals(before,draftCount());assertEquals(1,requests.size());
            assertSaved(host(turn),true);assertEquals("EXECUTION_STOPPED",host(turn).path("route").asString());
            assertEquals(result.path("assistantMessage"),submit(browser,id,turn,"把故障描述改成新观察",200).path("assistantMessage"));assertEquals(1,requests.size());
            verify(draftService,times(1)).update(anyLong(),anyLong(),any());
            saveCase("committed-ack-lost",turn,reply,original,current(browser,original),requests.size(),true);
        } finally {manager.setTransactionExecutionListeners(previous);}
    }

    @Test void diagnosticNoToolResponseProvesNoDraftSavedInThisTurn() throws Exception {
        var browser=customerA();var id=create(browser,orderA);var original=prepare(browser,id);String turn=UUID.randomUUID().toString();
        plan(workflow());var reply=submit(browser,id,turn,"把故障描述改成新观察",201).path("assistantMessage").path("response");
        assertTrue(reply.path("draft").isNull());assertEquals(original,current(browser,original));assertEquals(1,requests.size());
        assertFalse(host(turn).path("draftSaved").asBoolean());assertEquals("NOT_SAVED",host(turn).path("draftPersistence").asString());
        assertTrue(host(turn).path("replyPersisted").asBoolean());verify(draftService,never()).update(anyLong(),anyLong(),any());
        saveCase("no-write",turn,reply,original,current(browser,original),requests.size(),false);
    }

    @Test void diagnosticCommittedEditSurvivesSubsequentModelFailure() throws Exception {
        var browser=customerA();var id=create(browser,orderA);var original=prepare(browser,id);String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",Map.of("symptom","新观察")));
        var result=submit(browser,id,turn,"把故障描述改成新观察",201);var reply=result.path("assistantMessage").path("response");
        assertEquals("MODEL_UNAVAILABLE",reply.path("status").asString());assertEquals(1,reply.path("draft").path("version").asLong());assertEquals(2,requests.size());
        assertSaved(host(turn),true);assertEquals("MODEL_OR_PROTOCOL_FAILED",host(turn).path("route").asString());
        assertEquals(result.path("assistantMessage"),submit(browser,id,turn,"把故障描述改成新观察",200).path("assistantMessage"));assertEquals(2,requests.size());
        verify(draftService,times(1)).update(anyLong(),anyLong(),any());
        saveCase("saved-model-failure",turn,reply,original,current(browser,original),requests.size(),true);
    }

    @Test void diagnosticFailedCheckpointReadStaysUnknownDespiteInMemorySavedDraft() throws Exception {
        var browser=customerA();var id=create(browser,orderA);var original=prepare(browser,id);String turn=UUID.randomUUID().toString();var armed=new AtomicBoolean(true);
        var actualMapper=sqlSession.getMapper(ConversationMapper.class);
        doAnswer(invocation->{
            var rows=actualMapper.turn(id,a,turn);
            if(rows.size()==2 && rows.get(1).responseJson()!=null && json.readTree(rows.get(1).responseJson()).path("draft").path("version").asLong(-1)==1 && armed.compareAndSet(true,false))
                throw new org.springframework.dao.DataAccessResourceFailureException("LOCAL_CHECKPOINT_READ_UNAVAILABLE");
            return rows;
        }).when(observedMapper).turn(eq(id),eq(a),eq(turn));
        plan(call("prepareRepairDraft",Map.of("symptom","新观察")),workflow());
        var failed=browser.request("POST",path(id)+"/messages",input(turn,"把故障描述改成新观察"));
        assertTrue(failed.statusCode()>=500 && failed.statusCode()<600);assertFalse(armed.get());assertEquals(2,requests.size());
        var outcome=host(turn);assertTrue(outcome.path("draftSaved").isNull());assertEquals("UNKNOWN",outcome.path("draftPersistence").asString());assertFalse(outcome.path("replyPersisted").asBoolean());
        var recovered=submit(browser,id,turn,"把故障描述改成新观察",200).path("assistantMessage").path("response");
        assertEquals(1,recovered.path("draft").path("version").asLong());assertEquals(current(browser,original),recovered.path("draft"));assertEquals(2,requests.size());
        verify(draftService,times(1)).update(anyLong(),anyLong(),any());
        saveCase("checkpoint-read-unknown",turn,recovered,original,current(browser,original),requests.size(),true);
    }
}

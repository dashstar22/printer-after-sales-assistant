package local.printer.assistant;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated HTTP/MySQL with loopback protocol fixtures. No real model is used. */
class SymptomFullStopCompatibilityIT extends AssistantRepairIT {
    static final String VALUE="刚刚观察到的新情况。",COMMAND="把故障描述改成"+VALUE;
    static Path proof;
    @DynamicPropertySource static void proofProperties(DynamicPropertyRegistry registry) throws Exception {
        String configured=System.getenv("T031_SYMPTOM_FULL_STOP_PROOF_DIR");assertNotNull(configured);
        proof=Path.of(configured).toAbsolutePath().normalize();assertTrue(proof.toString().contains("t031-symptom-full-stop"));
        Files.createDirectories(proof);registry.add("app.assistant.observation-evidence-dir",()->proof.toString());
    }
    JsonNode host(String turn) throws Exception {
        try(var paths=Files.list(proof.resolve("edit"))){
            for(var path:paths.filter(p->p.getFileName().toString().startsWith(turn+"-")).toList()){
                var row=json.readTree(Files.readString(path));if("HOST_OUTCOME".equals(row.path("phase").asString()))return row;
            }
        }
        throw new AssertionError("HOST_OUTCOME missing");
    }
    void retain(String name,String turn,JsonNode original,JsonNode current,JsonNode reply,boolean saved) throws Exception {
        var outcome=host(turn);assertEquals(saved,outcome.path("draftSaved").asBoolean());
        assertEquals(saved?"SAVED":"NOT_SAVED",outcome.path("draftPersistence").asString());
        assertTrue(outcome.path("replyPersisted").asBoolean());
        Files.writeString(proof.resolve(name+".json"),json.writeValueAsString(Map.of("case",name,"turnId",turn,
            "originalDraft",original,"currentDraft",current,"reply",reply,"hostOutcome",outcome,
            "mode","REAL_HTTP_MYSQL_LOOPBACK_MODEL_SIMULATION","modelRequests",requests.size())),StandardOpenOption.CREATE_NEW);
    }
    JsonNode changed(Browser browser,JsonNode original,JsonNode reply) throws Exception {
        var updated=current(browser,original);assertEquals("DRAFT_PREPARED",reply.path("status").asString());
        assertEquals(original.path("id"),updated.path("id"));assertEquals(original.path("version").asLong()+1,updated.path("version").asLong());
        assertEquals(VALUE,updated.path("symptom").asString());assertEquals(updated,reply.path("draft"));
        for(String field:List.of("extra","contact","contactMethod"))assertEquals(original.path(field),updated.path(field));
        return updated;
    }
    @Test void punctuationMissingFullStopStoresUserLiteralAndKeepsConfirmationBoundary() throws Exception {
        var browser=customerA();String id=create(browser,orderA);var original=prepare(browser,id);var old=confirm(browser,original);
        long draftsBefore=draftCount(),ticketsBefore=tickets();String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",Map.of("symptom","刚刚观察到的新情况")),workflow());
        var result=submit(browser,id,turn,COMMAND,201);var reply=result.path("assistantMessage").path("response");
        var updated=changed(browser,original,reply);assertEquals(draftsBefore,draftCount());assertEquals(ticketsBefore,tickets());
        assertEquals("required",requests.get(0).path("tool_choice").asString());assertEquals(1,requests.get(0).path("tools").size());
        assertEquals("prepareRepairDraft",requests.get(0).path("tools").get(0).path("function").path("name").asString());
        assertEquals(original,detail(browser,id).path("messages").path("items").get(1).path("response").path("draft"));
        assertEquals(result.path("assistantMessage"),submit(browser,id,turn,COMMAND,200).path("assistantMessage"));assertEquals(2,requests.size());
        error(browser.request("POST",ordinaryPath(old),binding(old)),409,"CONFIRMATION_CONFLICT");assertEquals(ticketsBefore,tickets());
        assertNull(jdbc.queryForObject("SELECT ticket_id FROM repair_confirmations WHERE id=?",Long.class,old.path("confirmationId").asString()));
        var fresh=confirm(browser,updated);var operation=success(browser.request("POST",ordinaryPath(fresh),binding(fresh)),201);
        assertEquals(operation,success(browser.request("POST",ordinaryPath(fresh),binding(fresh)),200));
        assertEquals(ticketsBefore+1,tickets());singleTicket(json.readTree("{\"operation\":"+operation+"}"));
        retain("missing-full-stop",turn,original,updated,reply,true);
    }
    @Test void punctuationExactValueStillStoresExactly() throws Exception {
        var browser=customerA();String id=create(browser,orderA);var original=prepare(browser,id);String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",Map.of("symptom",VALUE)),workflow());
        var reply=submit(browser,id,turn,COMMAND,201).path("assistantMessage").path("response");
        retain("exact-full-stop",turn,original,changed(browser,original,reply),reply,true);
    }
    @Test void punctuationRepeatedToolAndSameTurnReplayDoNotWriteTwice() throws Exception {
        var browser=customerA();String id=create(browser,orderA);var original=prepare(browser,id);String turn=UUID.randomUUID().toString();
        var args=Map.of("symptom","刚刚观察到的新情况");plan(call("prepareRepairDraft",args),call("prepareRepairDraft",args),workflow());
        var result=submit(browser,id,turn,COMMAND,201);var reply=result.path("assistantMessage").path("response");
        var updated=changed(browser,original,reply);assertEquals(3,requests.size());
        assertEquals(result.path("assistantMessage"),submit(browser,id,turn,COMMAND,200).path("assistantMessage"));assertEquals(3,requests.size());
        var confirmation=confirm(browser,updated);String next=UUID.randomUUID().toString();plan(call("prepareRepairDraft",args),workflow());
        var noOp=submit(browser,id,next,COMMAND,201).path("assistantMessage").path("response");
        assertEquals(updated,noOp.path("draft"));assertEquals(updated,current(browser,original));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM repair_confirmations WHERE draft_id=?",Integer.class,updated.path("id").asLong()));
        assertEquals(updated.path("version"),confirmation.path("draftVersion"));
        retain("repeated-and-no-op",turn,original,updated,reply,true);
    }
    @Test void punctuationSavedLiteralSurvivesContinuationFailure() throws Exception {
        var browser=customerA();String id=create(browser,orderA);var original=prepare(browser,id);String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",Map.of("symptom","刚刚观察到的新情况")));
        var result=submit(browser,id,turn,COMMAND,201);var reply=result.path("assistantMessage").path("response");
        assertEquals("MODEL_UNAVAILABLE",reply.path("status").asString());assertEquals(VALUE,reply.path("draft").path("symptom").asString());
        assertEquals(1,reply.path("draft").path("version").asLong());assertEquals(current(browser,original),reply.path("draft"));
        assertEquals(result.path("assistantMessage"),submit(browser,id,turn,COMMAND,200).path("assistantMessage"));assertEquals(2,requests.size());
        retain("saved-continuation-failed",turn,original,current(browser,original),reply,true);
    }
    static Stream<Arguments> rejectedValues(){return Stream.of(
        Arguments.of("word",COMMAND,"刚刚观察到的新内容"),Arguments.of("ascii-stop",COMMAND,"刚刚观察到的新情况."),
        Arguments.of("exclamation",COMMAND,"刚刚观察到的新情况！"),Arguments.of("added",COMMAND,"刚刚观察到的新情况。更多"),
        Arguments.of("two-stops",COMMAND,"刚刚观察到的新情况。。"),Arguments.of("added-stop","把故障描述改成新观察","新观察。"),
        Arguments.of("repeated-source-stop","把故障描述改成新观察。。","新观察。"),
        Arguments.of("internal-stop","把故障描述改成温度3。报警。","温度3报警"),
        Arguments.of("number","把故障描述改成温度30度。","温度31度"),Arguments.of("negation","把故障描述改成未发现漏粉。","发现漏粉"));}
    @ParameterizedTest @MethodSource("rejectedValues")
    void punctuationOtherContentDifferencesRemainRejected(String name,String command,String supplied) throws Exception {
        var browser=customerA();String id=create(browser,orderA);var original=prepare(browser,id);long before=draftCount(),ticketsBefore=tickets();String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",Map.of("symptom",supplied)),workflow());
        var reply=submit(browser,id,turn,command,201).path("assistantMessage").path("response");
        assertEquals("TOOL_REJECTED",reply.path("status").asString());assertTrue(reply.path("draft").isNull());
        unchanged(browser,original,before);assertEquals(ticketsBefore,tickets());retain("reject-"+name,turn,original,current(browser,original),reply,false);
    }
    @ParameterizedTest @CsvSource({"extra,现场补充信息","contact,联系人","contactMethod,联系方式"})
    void punctuationOtherFieldsDoNotGainCompatibility(String field,String label) throws Exception {
        var browser=customerA();String id=create(browser,orderA);var original=prepare(browser,id);long before=draftCount();String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",Map.of(field,"独立新值")),workflow());
        var reply=submit(browser,id,turn,"把"+label+"改成独立新值。",201).path("assistantMessage").path("response");
        assertEquals("TOOL_REJECTED",reply.path("status").asString());unchanged(browser,original,before);
        retain("reject-field-"+field,turn,original,current(browser,original),reply,false);
    }
    @ParameterizedTest @ValueSource(strings={"extra","contact","contactMethod"})
    void punctuationAdditionalFieldChangeRejectsEntireWrite(String field) throws Exception {
        var browser=customerA();String id=create(browser,orderA);var original=prepare(browser,id);long before=draftCount();String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",Map.of("symptom","刚刚观察到的新情况",field,"不属于当前修改")),workflow());
        var reply=submit(browser,id,turn,COMMAND,201).path("assistantMessage").path("response");
        assertEquals("TOOL_REJECTED",reply.path("status").asString());unchanged(browser,original,before);
        retain("reject-additional-"+field,turn,original,current(browser,original),reply,false);
    }
    @Test void punctuationCrossCustomerCannotUseOwnedConversation() throws Exception {
        var browser=customerA();String id=create(browser,orderA);var original=prepare(browser,id);long before=draftCount();
        plan(call("prepareRepairDraft",Map.of("symptom","刚刚观察到的新情况")),workflow());
        error(customerB().request("POST",path(id)+"/messages",input(UUID.randomUUID().toString(),COMMAND)),404,"CONVERSATION_NOT_FOUND");
        assertTrue(requests.isEmpty());unchanged(browser,original,before);
    }
}

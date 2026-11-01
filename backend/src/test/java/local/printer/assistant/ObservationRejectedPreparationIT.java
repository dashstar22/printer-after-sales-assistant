package local.printer.assistant;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated HTTP/MySQL; every model response is an explicitly local protocol fixture. */
class ObservationRejectedPreparationIT extends AssistantRepairIT {
    static final String FIRST="只知道打印失败，现场信息还没记录。",GUESS="补充栏写你猜测的原因就行。";
    static Path proof;
    @DynamicPropertySource static void proofProperties(DynamicPropertyRegistry registry)throws Exception{
        proof=Path.of(Objects.requireNonNull(System.getenv("T031_OBSERVATION_REJECTION_PROOF_DIR"))).toAbsolutePath().normalize();
        assertTrue(proof.toString().contains("t031-observation-rejection-fix"));
        Files.createDirectories(proof);registry.add("app.assistant.observation-evidence-dir",()->proof.toString());
    }
    String firstConversation(Browser b)throws Exception{
        String id=create(b,orderA);
        mapper.insertMessage(id,a,UUID.randomUUID().toString(),"USER","故障描述：打印失败；联系人：模拟联系人甲；联系方式：模拟站内联系",null);
        return id;
    }
    Map<String,Long> business(){
        var result=new LinkedHashMap<String,Long>();
        for(String t:List.of("repair_drafts","repair_confirmations","tickets","ticket_events"))
            result.put(t,jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Long.class));
        return result;
    }
    JsonNode reply(Browser b,String id,String turn,String text)throws Exception{
        return submit(b,id,turn,text,201).path("assistantMessage").path("response");
    }
    void guidance(JsonNode reply,String status){
        assertEquals(status,reply.path("status").asString());assertTrue(reply.path("text").asString().contains("请补充：现场补充信息"));
        assertTrue(reply.path("text").asString().contains("不会猜测或代填"));
        assertTrue(reply.path("draft").isNull());assertTrue(reply.path("operation").isNull());assertTrue(reply.path("citations").isEmpty());
    }
    void retain(String name,Browser b,String id,String turn,JsonNode response,Map<String,Long> before)throws Exception{
        var sql=json.readTree(mapper.turn(id,a,turn).get(1).responseJson());assertEquals(response,sql);
        var page=detail(b,id);JsonNode found=null;
        for(var item:page.path("messages").path("items"))if(turn.equals(item.path("turnId").asString())&&"ASSISTANT".equals(item.path("role").asString()))found=item.path("response");
        assertEquals(response,found);assertEquals(before,business());
        var branch=json.readTree(Files.readString(proof.resolve(turn+".json")));
        Files.writeString(proof.resolve(name+".json"),json.writeValueAsString(Map.of("case",name,"conversationId",id,"turnId",turn,
            "reply",response,"sqlReply",sql,"httpReadback",found,"businessBefore",before,"businessAfter",business(),
            "branch",branch,"modelRequests",requests.size())),StandardOpenOption.CREATE_NEW);
    }
    @Test void rejectionOriginalTwoRoundsStayRejectedAndContinuePromptWithoutWrites()throws Exception{
        var b=customerA();String id=firstConversation(b);var before=business();
        for(int i=0;i<2;i++){
            String turn=UUID.randomUUID().toString();plan(call("prepareRepairDraft",fields()),workflow());
            var response=reply(b,id,turn,i==0?FIRST:GUESS);guidance(response,"TOOL_REJECTED");
            assertEquals(2,requests.size());assertTrue(requests.get(1).path("messages").toString().contains("TOOL_REJECTED"));
            retain("original-round"+(i+1),b,id,turn,response,before);
        }
    }
    @Test void rejectionWorkflowOnlyAndFollowingDeniedToolKeepPrompt()throws Exception{
        var b=customerA();String id=firstConversation(b);var before=business();String one=UUID.randomUUID().toString();
        plan(workflow());var first=reply(b,id,one,FIRST);guidance(first,"NEEDS_INPUT");retain("workflow-first",b,id,one,first,before);
        String two=UUID.randomUUID().toString();plan(call("prepareRepairDraft",Map.of()),answer("untrusted continuation"));
        var second=reply(b,id,two,GUESS);guidance(second,"TOOL_REJECTED");retain("workflow-then-rejection",b,id,two,second,before);
    }
    @Test void rejectionSameTurnReplayDoesNotCallModelOrWriteAgain()throws Exception{
        var b=customerA();String id=firstConversation(b);var before=business();String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",Map.of()),workflow());var result=submit(b,id,turn,FIRST,201);
        assertEquals(result.path("assistantMessage"),submit(b,id,turn,FIRST,200).path("assistantMessage"));assertEquals(2,requests.size());
        error(b.request("POST",path(id)+"/messages",input(turn,"不同输入")),409,"CONVERSATION_CONFLICT");
        guidance(result.path("assistantMessage").path("response"),"TOOL_REJECTED");
        retain("same-turn-replay",b,id,turn,result.path("assistantMessage").path("response"),before);
    }
    @Test void rejectionUnrelatedTopicNeverGainsObservationState()throws Exception{
        var b=customerA();String id=firstConversation(b);var before=business();String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",fields()),workflow());var result=reply(b,id,turn,"打印机报修处理要求");
        assertEquals("TOOL_REJECTED",result.path("status").asString());assertFalse(result.path("text").asString().contains("请补充"));
        retain("unrelated-topic",b,id,turn,result,before);
    }
    @Test void rejectionExistingTargetKeepsOriginalDraftAndRefusal()throws Exception{
        var b=customerA();String id=create(b,orderA);var original=prepare(b,id);var before=business();String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",fields()),workflow());var result=reply(b,id,turn,FIRST);
        assertEquals("TOOL_REJECTED",result.path("status").asString());assertFalse(result.path("text").asString().contains("请补充"));
        assertEquals(original,current(b,original));retain("existing-target",b,id,turn,result,before);
    }
    @Test void rejectionContinuationFailureIsNotPresentedAsSuccessfulPrompt()throws Exception{
        var b=customerA();String id=firstConversation(b);var before=business();String turn=UUID.randomUUID().toString();
        plan(call("prepareRepairDraft",Map.of()));var result=reply(b,id,turn,FIRST);
        assertEquals("MODEL_UNAVAILABLE",result.path("status").asString());assertTrue(result.path("draft").isNull());
        retain("model-failure",b,id,turn,result,before);
    }
    @Test void rejectionCrossCustomerIsDeniedBeforeModelCall()throws Exception{
        var b=customerA();String id=firstConversation(b);var before=business();
        plan(call("prepareRepairDraft",Map.of()),workflow());
        error(customerB().request("POST",path(id)+"/messages",input(UUID.randomUUID().toString(),FIRST)),404,"CONVERSATION_NOT_FOUND");
        assertTrue(requests.isEmpty());assertEquals(before,business());
    }
}

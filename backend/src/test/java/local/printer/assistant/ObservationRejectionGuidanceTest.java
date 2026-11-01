package local.printer.assistant;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static local.printer.assistant.ObservationContinuationTest.*;

class ObservationRejectionGuidanceTest {
    static final String FIRST="只知道打印失败，现场信息还没记录。";
    static final String REFUSAL=AssistantConversationService.UNAUTHORIZED_OBSERVATION_PREFIX+NEW_ASK;
    final ObservationContinuationTest fixture=new ObservationContinuationTest();
    @AfterEach void close(){fixture.close();}

    static List<ConversationMapper.Message> initial(String text){
        return List.of(new ConversationMapper.Message(1,"current","USER",text,null,NOW));
    }
    static List<ConversationMapper.Message> refused(String original,String current,long seconds){
        var json=JsonMapper.builder().build();
        var payload=new LinkedHashMap<String,Object>();payload.put("status","TOOL_REJECTED");payload.put("text",REFUSAL);
        payload.put("citations",List.of());payload.put("draft",null);payload.put("operation",null);
        String saved=json.writeValueAsString(payload);
        return new ArrayList<>(List.of(new ConversationMapper.Message(1,"first","USER",original,null,NOW.minusSeconds(seconds)),
            new ConversationMapper.Message(2,"first","ASSISTANT",REFUSAL,saved,NOW.minusSeconds(seconds)),
            new ConversationMapper.Message(3,"current","USER",current,null,NOW)));
    }
    static String rejectPreparation(Object state)throws Exception{
        var method=state.getClass().getDeclaredMethod("execute",ToolExecutionRequest.class);method.setAccessible(true);
        return (String)method.invoke(state,ToolExecutionRequest.builder().id("unit-call").name("prepareRepairDraft").arguments("{}").build());
    }
    Object bound(List<ConversationMapper.Message> history,String text,String target)throws Exception{
        return fixture.bound(history,text,target);
    }
    @ParameterizedTest @ValueSource(strings={FIRST,"现场信息还没有记录。","现场补充信息尚未提供","现场观察未确认。","只知道卡纸；现场情况尚未记录！"})
    void explicitUnknownObservationOnlyCreatesReadOnlyPrompt(String text){
        assertTrue(AssistantConversationService.initialMissingObservation(text));
        assertFalse(AssistantConversationService.creation(text));
        assertEquals(List.of("extra"),AssistantConversationService.pendingObservation(initial(text),text,true));
        assertEquals("INITIAL_MISSING",AssistantConversationService.observationState(initial(text),text,true).reason());
    }
    @ParameterizedTest @ValueSource(strings={"打印机报修处理要求","我之前的报修处理到哪一步了？","取消报修",
        "现场信息还没记录。然后提交","把现场补充信息改成未记录。","现场信息尚未记录，请准备草稿。","现场：已检查纸张"})
    void otherIntentsNeverEnterInitialPrompt(String text){
        assertFalse(AssistantConversationService.initialMissingObservation(text));
        assertTrue(AssistantConversationService.pendingObservation(initial(text),text,true).isEmpty());
    }
    @Test void initialStateNeedsOwnedCurrentMessageAndNoExistingTarget(){
        assertTrue(AssistantConversationService.pendingObservation(List.of(),FIRST,true).isEmpty());
        assertTrue(AssistantConversationService.pendingObservation(initial("其他输入"),FIRST,true).isEmpty());
        assertTrue(AssistantConversationService.pendingObservation(initial(FIRST),FIRST,false).isEmpty());
        var assistant=List.of(new ConversationMapper.Message(1,"current","ASSISTANT",FIRST,null,NOW));
        assertTrue(AssistantConversationService.pendingObservation(assistant,FIRST,true).isEmpty());
    }
    @Test void actualUnauthorizedToolRejectionRetainsStatusAndAddsOnlyTrustedPrompt()throws Exception{
        var state=bound(initial(FIRST),FIRST,"NONE");
        assertTrue(rejectPreparation(state).contains("TOOL_REJECTED"));
        var answer=finish(state,"{\"kind\":\"WORKFLOW\"}");
        assertEquals("TOOL_REJECTED",answer.status());assertEquals(REFUSAL,answer.text());assertTrue(answer.citations().isEmpty());
    }
    @Test void malformedModelContinuationNeverLeaksIntoRefusalGuidance()throws Exception{
        var state=bound(initial(FIRST),FIRST,"NONE");rejectPreparation(state);
        var answer=finish(state,"UNTRUSTED_SENTINEL");
        assertEquals("TOOL_REJECTED",answer.status());assertEquals(REFUSAL,answer.text());
    }
    @Test void validWorkflowWithoutToolKeepsConservativePrompt()throws Exception{
        assertEquals(NEW_ASK,finish(bound(initial(FIRST),FIRST,"NONE"),"{\"kind\":\"WORKFLOW\"}").text());
        var answer=finish(bound(initial(FIRST),FIRST,"NONE"),"{\"kind\":\"WORKFLOW\",\"intent\":\"ASK_MISSING\",\"missingFields\":[\"contact\"]}");
        assertEquals("NEEDS_INPUT",answer.status());assertTrue(answer.text().contains("现场补充信息"));assertTrue(answer.text().contains("联系人"));
    }
    @Test void refusalContinuationCarriesPromptButNeverCreatesAuthorization()throws Exception{
        var history=refused(FIRST,GUESS,20);
        assertEquals(List.of("extra"),AssistantConversationService.pendingObservation(history,GUESS,true));
        var state=bound(history,GUESS,"NONE");assertTrue(rejectPreparation(state).contains("TOOL_REJECTED"));
        assertEquals(REFUSAL,finish(state,"{\"kind\":\"WORKFLOW\"}").text());
    }
    @Test void arbitraryRefusalUnknownHistoryAndExpiryNeverRestartChain(){
        assertTrue(AssistantConversationService.pendingObservation(refused("查询资料",GUESS,20),GUESS,true).isEmpty());
        for(long age:new long[]{601,-1})
            assertTrue(AssistantConversationService.pendingObservation(refused(FIRST,GUESS,age),GUESS,true).isEmpty());
        var h=refused(FIRST,GUESS,20);var a=h.get(1);
        h.set(1,new ConversationMapper.Message(2,"foreign-turn","ASSISTANT",a.content(),a.responseJson(),a.createdAt()));
        assertTrue(AssistantConversationService.pendingObservation(h,GUESS,true).isEmpty());
    }
    @Test void unknownWriteAndRetrievalFailuresRemainAuthoritative()throws Exception{
        var state=bound(initial(FIRST),FIRST,"NONE");rejectPreparation(state);
        set(state,"writeFailure","WRITE_RESULT_UNKNOWN");
        assertEquals("WRITE_RESULT_UNKNOWN",finish(state,"{\"kind\":\"WORKFLOW\"}").status());
        for(String status:List.of("NO_EVIDENCE","RETRIEVAL_UNAVAILABLE","EVIDENCE_NOT_AUTHORIZED")){
            state=bound(initial(FIRST),FIRST,"NONE");rejectPreparation(state);set(state,"retrievalStatus",status);
            assertEquals(status,finish(state,"{\"kind\":\"WORKFLOW\"}").status());
        }
    }
    @Test void existingTargetAndUnrelatedToolKeepOriginalRefusal()throws Exception{
        var state=bound(initial(FIRST),FIRST,"ONE_OPEN");rejectPreparation(state);
        assertFalse(finish(state,"{\"kind\":\"WORKFLOW\"}").text().contains("请补充"));
        state=bound(initial(FIRST),FIRST,"NONE");rejectPreparation(state);
        var field=state.getClass().getDeclaredField("trace");field.setAccessible(true);
        @SuppressWarnings("unchecked") var trace=(List<Map<String,Object>>)field.get(state);
        trace.add(Map.of("name","createServiceTicket","status","PAGE_CONFIRMATION_REQUIRED"));
        assertFalse(finish(state,"{\"kind\":\"WORKFLOW\"}").text().contains("请补充"));
    }
}

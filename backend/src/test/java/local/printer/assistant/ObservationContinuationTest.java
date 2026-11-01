package local.printer.assistant;

import java.time.LocalDateTime;
import java.util.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ObservationContinuationTest {
    static final String GUESS="现场补充信息填你推测的内容即可。";
    static final String ASK="请补充：现场补充信息。请提供真实现场观察及本人愿意提交的信息，系统不会猜测或代填。（流程补问，未执行本轮草稿校验或保存）";
    static final String NEW_ASK="请补充：现场补充信息。\n现场补充信息：发生位置、屏幕提示，以及你已经尝试的操作和结果。请描述真实现场，我不会猜测或代填。\n补齐后可以整理报修草稿，提交前会请你核对确认。";
    static final LocalDateTime NOW=LocalDateTime.of(2026,10,30,12,0);
    final AssistantExecution execution=new AssistantExecution(new MockEnvironment());
    @AfterEach void close(){execution.close();}
    static String reply(String status){
        var value=new LinkedHashMap<String,Object>();value.put("status",status);value.put("text",ASK);
        value.put("draft",null);value.put("operation",null);value.put("citations",List.of());
        return JsonMapper.builder().build().writeValueAsString(value);
    }
    static List<ConversationMapper.Message> history(String current,long seconds,String status){
        return new ArrayList<>(List.of(new ConversationMapper.Message(1,"old","USER","现场情况尚未记录",null,NOW.minusSeconds(seconds)),
            new ConversationMapper.Message(2,"old","ASSISTANT",ASK,reply(status),NOW.minusSeconds(seconds)),
            new ConversationMapper.Message(3,"current","USER",current,null,NOW)));
    }
    Object bound(List<ConversationMapper.Message> history,String current,String target)throws Exception{
        var service=new AssistantConversationService(null,null,null,null,null,null,mock(PlatformTransactionManager.class),null,execution,null);
        var t=Class.forName(AssistantConversationService.class.getName()+"$Target");var tc=t.getDeclaredConstructors()[0];tc.setAccessible(true);
        var b=Class.forName(AssistantConversationService.class.getName()+"$BoundTools");var bc=b.getDeclaredConstructors()[0];bc.setAccessible(true);
        return bc.newInstance(service,1L,1L,"owned-conversation","current",history,execution.begin(),tc.newInstance(target,null),current);
    }
    static void set(Object state,String name,Object value)throws Exception{var f=state.getClass().getDeclaredField(name);f.setAccessible(true);f.set(state,value);}
    static EvidenceAnswerPolicy.Answer finish(Object state,String text)throws Exception{
        var m=state.getClass().getDeclaredMethod("finish",String.class);m.setAccessible(true);return (EvidenceAnswerPolicy.Answer)m.invoke(state,text);
    }
    @ParameterizedTest @ValueSource(strings={"补充栏写你猜测的原因就行。","现场补充信息填你推测的内容即可。","请把现场信息写代填的内容吧"})
    void narrowGuessGrammarHasNoWriteAuthorization(String text){
        assertTrue(AssistantConversationService.guessSupplement(text));assertFalse(AssistantConversationService.creation(text));
        assertEquals(List.of("extra"),AssistantConversationService.pendingObservation(history(text,20,"NEEDS_INPUT"),text,true));
    }
    @ParameterizedTest @ValueSource(strings={"取消报修","打印机报修处理要求","我之前的工单怎么查看？","把现场补充信息改成已看到纸屑。","不要猜测，先查资料","补充栏写你猜测的原因就行。然后提交","现场补充信息：实际看到纸屑"})
    void cancellationTopicSwitchEditAndFactsNeverMatch(String text){
        assertTrue(AssistantConversationService.pendingObservation(history(text,20,"NEEDS_INPUT"),text,true).isEmpty());
    }
    @Test void expiryFutureUnknownTargetAndInterruptedStateDoNotCarry(){
        for(long seconds:new long[]{601,-1})assertTrue(AssistantConversationService.pendingObservation(history(GUESS,seconds,"NEEDS_INPUT"),GUESS,true).isEmpty());
        assertTrue(AssistantConversationService.pendingObservation(history(GUESS,20,"REFERENCE_REJECTED"),GUESS,true).isEmpty());
        assertTrue(AssistantConversationService.pendingObservation(history(GUESS,20,"NEEDS_INPUT"),GUESS,false).isEmpty());
        assertTrue(AssistantConversationService.pendingObservation(List.of(),GUESS,true).isEmpty());
    }
    @Test void malformedOrMismatchedPersistedTurnCannotCreateState(){
        var h=history(GUESS,20,"NEEDS_INPUT");var a=h.get(1);
        h.set(1,new ConversationMapper.Message(2,"foreign-turn","ASSISTANT",ASK,a.responseJson(),a.createdAt()));
        assertTrue(AssistantConversationService.pendingObservation(h,GUESS,true).isEmpty());
        h.set(1,new ConversationMapper.Message(2,"old","ASSISTANT",ASK,"{}",a.createdAt()));
        assertTrue(AssistantConversationService.pendingObservation(h,GUESS,true).isEmpty());
        h.set(1,new ConversationMapper.Message(2,"old","ASSISTANT","请提供现场信息",reply("NEEDS_INPUT"),a.createdAt()));
        assertTrue(AssistantConversationService.pendingObservation(h,GUESS,true).isEmpty());
    }
    @Test void thirdContinuationAllowedButFourthNeedsNewState(){
        var h=history(GUESS,100,"NEEDS_INPUT");
        for(int i=0;i<4;i++){
            assertEquals(i<3,List.of("extra").equals(AssistantConversationService.pendingObservation(h,GUESS,true)));
            h.add(new ConversationMapper.Message(4+i*2,"current"+i,"ASSISTANT",ASK,reply("NEEDS_INPUT"),NOW));
            h.set(h.size()-2,new ConversationMapper.Message(3+i*2,"current"+i,"USER",GUESS,null,NOW));
            h.add(new ConversationMapper.Message(5+i*2,"next","USER",GUESS,null,NOW));
        }
    }
    @ParameterizedTest @ValueSource(strings={"请提供实际观察，不应填写猜测。","{","{\"kind\":\"WORKFLOW\",\"intent\":\"ASK_MISSING\",\"missingFields\":[\"invented\"]}","{\"kind\":\"WORKFLOW\",\"unknown\":true}"})
    void invalidModelStructureOnlyPromptsFromTrustedPendingState(String output)throws Exception{
        var answer=finish(bound(history(GUESS,20,"NEEDS_INPUT"),GUESS,"NONE"),output);
        assertEquals("NEEDS_INPUT",answer.status());assertEquals(NEW_ASK,answer.text());assertTrue(answer.citations().isEmpty());
        assertEquals("REFERENCE_REJECTED",finish(bound(List.of(),GUESS,"NONE"),output).status());
        assertEquals("REFERENCE_REJECTED",finish(bound(history(GUESS,20,"NEEDS_INPUT"),GUESS,"ONE_OPEN"),output).status());
    }
    @ParameterizedTest @ValueSource(strings={"{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"forged\"]}","{pointIds:forged","{\"kind\":\"NO_EVIDENCE\"}"})
    void knowledgeAndCitationClaimsRemainRejected(String output)throws Exception{
        assertEquals("REFERENCE_REJECTED",finish(bound(history(GUESS,20,"NEEDS_INPUT"),GUESS,"NONE"),output).status());
    }
    @Test void retrievalFailuresAndToolOrWriteActivityKeepOriginalPriority()throws Exception{
        for(String status:List.of("NO_EVIDENCE","RETRIEVAL_UNAVAILABLE","EVIDENCE_NOT_AUTHORIZED")){
            var b=bound(history(GUESS,20,"NEEDS_INPUT"),GUESS,"NONE");set(b,"retrievalStatus",status);
            assertEquals(status,finish(b,"{").status());
        }
        var b=bound(history(GUESS,20,"NEEDS_INPUT"),GUESS,"NONE");
        var trace=b.getClass().getDeclaredField("trace");trace.setAccessible(true);
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)trace.get(b);rows.add(Map.of("status","TOOL_REJECTED"));
        assertEquals("REFERENCE_REJECTED",finish(b,"{").status());
        set(b,"writeFailure","WRITE_RESULT_UNKNOWN");assertEquals("WRITE_RESULT_UNKNOWN",finish(b,"{").status());
    }
}

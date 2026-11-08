package local.printer.assistant;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import local.printer.config.DependencyProperties;
import local.printer.knowledge.KnowledgeRetrievalService;
import local.printer.order.OrderResponse;
import local.printer.order.OrderService;
import local.printer.ticket.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service unit tests with an in-memory mapper and a loopback model protocol fixture; no database or Spring context. */
class ObservationSemanticServiceTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final LocalDateTime NOW=LocalDateTime.of(2026,11,8,12,0);
    static final String ID="0e7f795e-1623-4f5a-81d9-c9b08e66e516";
    static final String AUTH="1e7f795e-1623-4f5a-81d9-c9b08e66e516";
    static final long CUSTOMER=1,ORDER=2;
    Fixture fixture;

    @BeforeEach void createFixture()throws Exception{fixture=new Fixture();}
    @AfterEach void close(){fixture.close();}

    @ParameterizedTest @ValueSource(strings={"打印时没有什么异常声音。","屏幕无错误码，已重启。","进纸口正常，已检查纸盘。"})
    void factAppliesOnlyTheCurrentSavedLiteralAndCheckpointsTheCollection(String input){
        var original=fixture.state.get();String turn=UUID.randomUUID().toString();
        var result=fixture.submit(turn,"\u3000"+input+"\u00a0");var current=fixture.state.get();
        assertEquals("NEEDS_INPUT",result.assistantMessage().response().status());
        assertEquals(input,result.userMessage().content());assertEquals(input,current.extra());
        assertEquals(original.symptom(),current.symptom());assertEquals(original.contact(),current.contact());assertEquals(original.contactMethod(),current.contactMethod());
        assertEquals("COLLECTING",current.phase());assertEquals("contact",current.pendingField());
        assertEquals(original.authorizedTurn(),current.authorizedTurn());assertEquals(original.expiresAt(),current.expiresAt());
        assertEquals(original.revision()+1,current.revision());assertEquals(turn,current.lastTurn());
        assertEquals(1,fixture.stateWrites.size());var write=fixture.stateWrites.get(0);
        assertTrue(fixture.checkpoints.stream().anyMatch(c->c.transaction()==write.transaction()&&"NEEDS_INPUT".equals(c.status())&&current.equals(c.state())));
        fixture.oneClassificationOf(input);fixture.noBusinessTools();
    }

    @ParameterizedTest @ValueSource(strings={"{\"kind\":\"QUESTION\"}","{\"kind\":\"UNCLEAR\"}","{", "{}",
        "{\"kind\":\"FACT\",\"extra\":\"模型代填\"}","{\"kind\":\"WORKFLOW\"}","{\"kind\":\"FACT\",\"kind\":\"QUESTION\"}"})
    void nonFactAndMalformedRepliesNeverFillOrFallBackToGeneralTools(String output){
        fixture.output.set(output);var original=fixture.state.get();
        var result=fixture.submit("打印机卡纸该怎么处理");
        assertEquals("NEEDS_INPUT",result.assistantMessage().response().status());
        assertEquals(original,fixture.state.get());assertTrue(fixture.stateWrites.isEmpty());
        fixture.oneClassificationOf("打印机卡纸该怎么处理");fixture.noBusinessTools();
    }

    @Test void supplierFailureDoesNotRetryOrEnterTheGeneralModelLoop(){
        fixture.status.set(503);var original=fixture.state.get();
        var result=fixture.submit("打印时没有什么异常声音");
        assertEquals("NEEDS_INPUT",result.assistantMessage().response().status());assertEquals(original,fixture.state.get());
        assertTrue(fixture.stateWrites.isEmpty());fixture.oneClassificationOf("打印时没有什么异常声音");fixture.noBusinessTools();
    }

    @Test void latestUserChangedWithoutARevisionChangeDiscardsTheProposal(){
        var original=fixture.state.get();
        fixture.beforeReply.set(()->fixture.appendUser(UUID.randomUUID().toString(),"请准备报修"));
        var result=fixture.submit("打印时没有什么异常声音");
        assertEquals("NEEDS_INPUT",result.assistantMessage().response().status());assertEquals(original,fixture.state.get());
        assertTrue(fixture.stateWrites.isEmpty());fixture.oneClassificationOf("打印时没有什么异常声音");fixture.noBusinessTools();
    }

    @Test void changedLatestMessageIdIsRejectedEvenWhenTurnAndTextAreIdentical(){
        var original=fixture.state.get();String turn=UUID.randomUUID().toString(),input="打印时没有什么异常声音";
        // Override the re-read identity only; this does not model inserting a duplicate SQL turn.
        fixture.beforeReply.set(()->fixture.latestOverride.set(new ConversationMapper.Message(fixture.ids.incrementAndGet(),turn,"USER",input,null,NOW)));
        var result=fixture.submit(turn,input);
        assertEquals("NEEDS_INPUT",result.assistantMessage().response().status());assertEquals(original,fixture.state.get());
        assertTrue(fixture.stateWrites.isEmpty());verify(fixture.mapper,never()).updateDialogueState(any(),anyLong());
        fixture.oneClassificationOf(input);fixture.noBusinessTools();
    }

    @Test void aNewerWindowCancellationCannotBeOverwrittenByTheOldProposal(){
        var original=fixture.state.get();var cancelled=new AtomicReference<DialogueState>();
        fixture.beforeReply.set(()->{
            String newer=UUID.randomUUID().toString();fixture.appendUser(newer,"取消填写报修");
            cancelled.set(original.next("CANCELLED",original.values(),newer));fixture.state.set(cancelled.get());
        });
        var result=fixture.submit("打印时没有什么异常声音");
        assertEquals("NEEDS_INPUT",result.assistantMessage().response().status());assertEquals(cancelled.get(),fixture.state.get());
        assertTrue(fixture.stateWrites.isEmpty());fixture.oneClassificationOf("打印时没有什么异常声音");fixture.noBusinessTools();
    }

    @Test void aChangedRevisionCannotBeAppliedEvenWhenTheCurrentUserIsStillLatest(){
        var original=fixture.state.get();var changed=original.next("PAUSED",original.values(),original.lastTurn());
        fixture.beforeReply.set(()->fixture.state.set(changed));
        assertEquals("NEEDS_INPUT",fixture.submit("打印时没有什么异常声音").assistantMessage().response().status());
        assertEquals(changed,fixture.state.get());assertTrue(fixture.stateWrites.isEmpty());
        fixture.oneClassificationOf("打印时没有什么异常声音");fixture.noBusinessTools();
    }

    @ParameterizedTest @ValueSource(strings={"authorization","expiry","symptom"})
    void changedSnapshotContentIsRejectedEvenIfItsRevisionIsUnchanged(String changedField){
        var original=fixture.state.get();
        var changed=new DialogueState(original.conversationId(),original.customerId(),original.orderId(),original.phase(),
            "symptom".equals(changedField)?"出纸口卡住":original.symptom(),original.extra(),original.contact(),original.contactMethod(),original.pendingField(),
            "authorization".equals(changedField)?UUID.randomUUID().toString():original.authorizedTurn(),
            "expiry".equals(changedField)?original.expiresAt().plusMinutes(1):original.expiresAt(),original.revision(),original.lastTurn());
        fixture.beforeReply.set(()->fixture.state.set(changed));
        var result=fixture.submit("打印时没有什么异常声音");
        assertEquals("NEEDS_INPUT",result.assistantMessage().response().status());assertEquals(original.revision(),changed.revision());
        assertEquals(changed,fixture.state.get());assertTrue(fixture.stateWrites.isEmpty());verify(fixture.mapper,never()).updateDialogueState(any(),anyLong());
        fixture.oneClassificationOf("打印时没有什么异常声音");fixture.noBusinessTools();
    }

    @Test void aNewTargetAssociationDiscardsTheProposal(){
        var original=fixture.state.get();
        fixture.beforeReply.set(()->fixture.associations.set(List.of(new ConversationMapper.DraftAssociation("malformed-target"))));
        assertEquals("NEEDS_INPUT",fixture.submit("打印时没有什么异常声音").assistantMessage().response().status());
        assertEquals(original,fixture.state.get());assertTrue(fixture.stateWrites.isEmpty());
        fixture.oneClassificationOf("打印时没有什么异常声音");fixture.noBusinessTools();
    }

    @Test void reachingExactDatabaseExpiryDuringClassificationDiscardsTheProposal(){
        var original=fixture.state.get();fixture.beforeReply.set(()->fixture.clock.set(original.expiresAt()));
        assertEquals("NEEDS_INPUT",fixture.submit("打印时没有什么异常声音").assistantMessage().response().status());
        assertNull(fixture.state.get().extra());assertEquals(original.values(),fixture.state.get().values());
        assertEquals(original.expiresAt(),fixture.state.get().expiresAt());assertTrue(fixture.stateWrites.isEmpty());
        fixture.oneClassificationOf("打印时没有什么异常声音");fixture.noBusinessTools();
    }

    @Test void expiryWhileRecheckingTheTargetAlsoDiscardsTheProposal(){
        var original=fixture.state.get();
        fixture.beforeReply.set(()->when(fixture.mapper.draftAssociations(ID,CUSTOMER)).thenAnswer(invocation->{
            fixture.clock.set(original.expiresAt());return List.of();
        }));
        assertEquals("NEEDS_INPUT",fixture.submit("打印时没有什么异常声音").assistantMessage().response().status());
        assertEquals(original,fixture.state.get());assertTrue(fixture.stateWrites.isEmpty());
        fixture.oneClassificationOf("打印时没有什么异常声音");fixture.noBusinessTools();
    }

    @Test void compareAndSetFailureDoesNotRetryTheProposal(){
        fixture.rejectUpdate.set(true);var original=fixture.state.get();
        assertEquals("NEEDS_INPUT",fixture.submit("打印时没有什么异常声音").assistantMessage().response().status());
        assertEquals(original,fixture.state.get());assertTrue(fixture.stateWrites.isEmpty());
        verify(fixture.mapper,times(1)).updateDialogueState(any(),eq(original.revision()));
        fixture.oneClassificationOf("打印时没有什么异常声音");fixture.noBusinessTools();
    }

    @Test void anUnknownApplyTransportResultStopsWithoutASecondApplication(){
        fixture.updateFailure.set(new DataAccessResourceFailureException("in-memory transport fixture"));
        var original=fixture.state.get();
        var result=fixture.submit("打印时没有什么异常声音");
        assertEquals("WRITE_RESULT_UNKNOWN",result.assistantMessage().response().status());
        assertEquals(original,fixture.state.get());verify(fixture.mapper,times(1)).updateDialogueState(any(),eq(original.revision()));
        fixture.oneClassificationOf("打印时没有什么异常声音");fixture.noBusinessTools();
    }

    @Test void possiblyAppliedWriteStaysUnknownAndItsReplayNeverAppliesAgain(){
        // Retain a visible effect before throwing. The fixture intentionally makes no claim about real SQL rollback.
        fixture.effectThenFailure.set(new DataAccessResourceFailureException("effect visible but acknowledgement unavailable"));
        var original=fixture.state.get();String turn=UUID.randomUUID().toString(),input="打印时没有什么异常声音";
        var result=fixture.submit(turn,input);var response=result.assistantMessage().response();var visible=fixture.state.get();
        assertEquals("WRITE_RESULT_UNKNOWN",response.status());assertFalse(response.text().contains("没有填入"));assertFalse(response.text().contains("未填入"));
        assertNull(response.draft());assertNull(response.operation());assertEquals(input,visible.extra());assertEquals(original.revision()+1,visible.revision());
        assertEquals(turn,visible.lastTurn());assertEquals(original.authorizedTurn(),visible.authorizedTurn());assertEquals(original.expiresAt(),visible.expiresAt());
        assertEquals(1,fixture.stateWrites.size());var replay=fixture.submit(turn,input);
        assertTrue(replay.replayed());assertEquals(result.assistantMessage(),replay.assistantMessage());assertEquals(visible,fixture.state.get());
        assertEquals(1,fixture.stateWrites.size());verify(fixture.mapper,times(1)).updateDialogueState(any(),eq(original.revision()));
        fixture.oneClassificationOf(input);fixture.noBusinessTools();
    }

    @Test void sameTurnReplaysTheSavedResultAndDifferentTextConflictsWithoutNewCalls(){
        String turn=UUID.randomUUID().toString(),input="打印时没有什么异常声音";
        var first=fixture.submit(turn,input);var saved=fixture.state.get();
        var replay=fixture.submit(turn,input);
        assertTrue(replay.replayed());assertEquals(first.assistantMessage(),replay.assistantMessage());assertEquals(saved,fixture.state.get());
        assertThrows(ConversationService.Conflict.class,()->fixture.submit(turn,"屏幕正常"));
        assertEquals(1,fixture.stateWrites.size());fixture.oneClassificationOf(input);fixture.noBusinessTools();
    }

    @Test void replayWhileTheOriginalTurnIsPendingDoesNotClassifyAgain(){
        String turn=UUID.randomUUID().toString(),input="打印时没有什么异常声音";
        var pendingReplay=new AtomicReference<ConversationResponse.Turn>();
        fixture.beforeReply.set(()->pendingReplay.set(fixture.submit(turn,input)));
        assertEquals("NEEDS_INPUT",fixture.submit(turn,input).assistantMessage().response().status());
        assertTrue(pendingReplay.get().replayed());assertEquals("RESULT_UNKNOWN",pendingReplay.get().assistantMessage().response().status());
        assertEquals(1,fixture.stateWrites.size());fixture.oneClassificationOf(input);fixture.noBusinessTools();
    }

    @Test void ambiguousReferenceIsClarifiedBeforeAnySemanticCall(){
        var original=fixture.state.get();var result=fixture.submit("那具体怎么操作");
        assertEquals("NEEDS_INPUT",result.assistantMessage().response().status());assertEquals(original,fixture.state.get());
        assertTrue(fixture.requests.isEmpty());assertTrue(fixture.stateWrites.isEmpty());fixture.noBusinessTools();
    }

    @Test void resolvedKnowledgeReferenceAlsoStaysOutsideTheSemanticBranch(){
        String prior=UUID.randomUUID().toString();
        fixture.appendUser(prior,"打印机卡纸怎么处理？");
        var answer=JSON.writeValueAsString(Map.of("status","ANSWERED","citations",List.of(Map.of("pointId","fixture-source"))));
        fixture.rows.add(new ConversationMapper.Message(fixture.ids.incrementAndGet(),prior,"ASSISTANT","fixture knowledge answer",answer,NOW));
        var original=fixture.state.get();var result=fixture.submit("那具体怎么操作");
        assertEquals("NEEDS_INPUT",result.assistantMessage().response().status());assertEquals(original,fixture.state.get());
        assertTrue(fixture.requests.isEmpty());assertTrue(fixture.stateWrites.isEmpty());fixture.noBusinessTools();
    }

    @Test void featureIsDisabledByDefault(){
        ReflectionTestUtils.setField(fixture.service,"observationSemantics",false);
        var original=fixture.state.get();var result=fixture.submit("打印时没有什么异常声音");
        assertEquals("NEEDS_INPUT",result.assistantMessage().response().status());assertEquals(original,fixture.state.get());
        assertTrue(fixture.requests.isEmpty());assertTrue(fixture.stateWrites.isEmpty());fixture.noBusinessTools();
    }

    @Test void completingExtraUsesTheExistingDraftPreparationWithTheNewManagedState(){
        fixture.onlyExtraMissing();var original=fixture.state.get();String turn=UUID.randomUUID().toString(),input="打印时没有什么异常声音。";
        when(fixture.drafts.create(eq(CUSTOMER),any())).thenAnswer(invocation->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            DraftService.Input value=invocation.getArgument(1);
            assertEquals(ORDER,value.orderId());assertEquals(original.symptom(),value.symptom());assertEquals(input,value.extra());
            assertEquals(original.contact(),value.contact());assertEquals(original.contactMethod(),value.contactMethod());assertNull(value.expectedVersion());
            return new DraftResponse(55,ORDER,value.symptom(),value.extra(),value.contact(),value.contactMethod(),0,fixture.order);
        });
        var result=fixture.submit(turn,input);var response=result.assistantMessage().response();
        assertEquals("DRAFT_PREPARED",response.status());assertNotNull(response.draft());assertEquals(input,response.draft().extra());assertNull(response.operation());
        var prepared=fixture.state.get();assertEquals("PREPARED",prepared.phase());assertEquals(input,prepared.extra());assertEquals(turn,prepared.lastTurn());
        assertEquals(original.authorizedTurn(),prepared.authorizedTurn());assertEquals(original.expiresAt(),prepared.expiresAt());assertEquals(original.revision()+2,prepared.revision());
        assertEquals(2,fixture.stateWrites.size());assertEquals("COLLECTING",fixture.stateWrites.get(0).state().phase());
        var preparation=fixture.stateWrites.get(1);assertTrue(fixture.checkpoints.stream().anyMatch(c->c.transaction()==preparation.transaction()&&"DRAFT_PREPARED".equals(c.status())));
        verify(fixture.drafts,times(1)).create(eq(CUSTOMER),any());verifyNoInteractions(fixture.retrieval,fixture.tickets);
        fixture.oneClassificationOf(input);
        var replay=fixture.submit(turn,input);assertTrue(replay.replayed());assertEquals(result.assistantMessage(),replay.assistantMessage());
        verify(fixture.drafts,times(1)).create(eq(CUSTOMER),any());fixture.oneClassificationOf(input);
    }

    @Test void draftValidationFailureDoesNotReclassifyOrResaveTheReplayedTurn(){
        fixture.onlyExtraMissing();when(fixture.drafts.create(eq(CUSTOMER),any())).thenThrow(new DraftService.InvalidInput());
        String turn=UUID.randomUUID().toString(),input="打印时没有什么异常声音";var result=fixture.submit(turn,input);
        assertEquals("TOOL_REJECTED",result.assistantMessage().response().status());assertEquals("COLLECTING",fixture.state.get().phase());
        assertEquals(input,fixture.state.get().extra());assertTrue(fixture.state.get().missing().isEmpty());
        assertTrue(fixture.submit(turn,input).replayed());verify(fixture.drafts,times(1)).create(eq(CUSTOMER),any());
        fixture.oneClassificationOf(input);verifyNoInteractions(fixture.retrieval,fixture.tickets);
    }

    private record StateWrite(DialogueState state,long transaction){}
    private record Checkpoint(String status,DialogueState state,long transaction){}

    /** Records transaction boundaries only. This fixture does not emulate SQL locks, commits or rollback recovery. */
    private static final class MemoryTransactions extends AbstractPlatformTransactionManager {
        final AtomicInteger active=new AtomicInteger();final AtomicLong sequence=new AtomicLong();final ThreadLocal<Long> current=new ThreadLocal<>();
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){current.set(sequence.incrementAndGet());active.incrementAndGet();}
        @Override protected void doCommit(DefaultTransactionStatus status){}
        @Override protected void doRollback(DefaultTransactionStatus status){}
        @Override protected void doCleanupAfterCompletion(Object transaction){current.remove();active.decrementAndGet();}
        long id(){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return Objects.requireNonNull(current.get());}
    }

    private static final class Fixture implements AutoCloseable {
        final ConversationMapper mapper=mock(ConversationMapper.class);
        final OrderService orders=mock(OrderService.class);
        final DraftService drafts=mock(DraftService.class);
        final TicketMapper ticketMapper=mock(TicketMapper.class);
        final CreateServiceTicketTool tickets=mock(CreateServiceTicketTool.class);
        final KnowledgeRetrievalService retrieval=mock(KnowledgeRetrievalService.class);
        final MemoryTransactions transactions=new MemoryTransactions();
        final AssistantExecution execution=new AssistantExecution(new MockEnvironment().withProperty("ASSISTANT_READ_RETRIES","1"));
        final AtomicReference<LocalDateTime> clock=new AtomicReference<>(NOW);
        final AtomicReference<DialogueState> state=new AtomicReference<>(new DialogueState(ID,CUSTOMER,ORDER,"COLLECTING","反复卡纸",null,null,null,"extra",AUTH,NOW.plusMinutes(10),7,AUTH));
        final List<ConversationMapper.Message> rows=new CopyOnWriteArrayList<>();
        final List<StateWrite> stateWrites=new CopyOnWriteArrayList<>();
        final List<Checkpoint> checkpoints=new CopyOnWriteArrayList<>();
        final AtomicReference<List<ConversationMapper.DraftAssociation>> associations=new AtomicReference<>(List.of());
        final AtomicLong ids=new AtomicLong(10);
        final AtomicBoolean rejectUpdate=new AtomicBoolean();
        final AtomicReference<RuntimeException> updateFailure=new AtomicReference<>();
        final AtomicReference<RuntimeException> effectThenFailure=new AtomicReference<>();
        final AtomicReference<ConversationMapper.Message> latestOverride=new AtomicReference<>();
        final AtomicReference<String> output=new AtomicReference<>("{\"kind\":\"FACT\"}");
        final AtomicInteger status=new AtomicInteger(200);
        final AtomicReference<Runnable> beforeReply=new AtomicReference<>(()->{});
        final AtomicReference<Throwable> protocolFailure=new AtomicReference<>();
        final List<JsonNode> requests=new CopyOnWriteArrayList<>();
        final HttpServer model;
        final AssistantConversationService service;
        final OrderResponse order=new OrderResponse(ORDER,"TEST-ORDER","SIM-PRINTER","Synthetic","SYNTHETIC-SERIAL",LocalDate.of(2026,11,1),true,true);

        Fixture()throws Exception{
            model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            model.createContext("/",exchange->{
                try{
                    requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                    assertEquals(0,transactions.active.get(),"Semantic provider must run outside every service write transaction");
                    beforeReply.getAndSet(()->{}).run();
                    byte[] body=JSON.writeValueAsBytes(Map.of("id","semantic-fixture","object","chat.completion","model","semantic-protocol-fixture",
                        "choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",output.get()),"finish_reason","stop"))));
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);
                }catch(Throwable failure){protocolFailure.set(failure);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){/* Preserve the original fixture failure. */}}
                finally{exchange.close();}
            });model.start();
            var config=new DependencyProperties(new DependencyProperties.Qdrant(URI.create("http://127.0.0.1:9")),
                new DependencyProperties.Ai(true,URI.create("http://127.0.0.1:"+model.getAddress().getPort()+"/v1"),"semantic-protocol-fixture","loopback-fixture"));
            var conversation=new ConversationMapper.Conversation(ID,CUSTOMER,ORDER,NOW.minusMinutes(1));
            when(mapper.lockOwned(ID,CUSTOMER)).thenReturn(conversation);when(mapper.owned(ID,CUSTOMER)).thenReturn(conversation);
            when(orders.detail(CUSTOMER,ORDER)).thenReturn(order);
            when(mapper.turn(eq(ID),eq(CUSTOMER),anyString())).thenAnswer(invocation->rows.stream().filter(m->m.turnId().equals(invocation.getArgument(2))).sorted(Comparator.comparingLong(ConversationMapper.Message::id)).toList());
            when(mapper.recent(eq(ID),eq(CUSTOMER),anyLong())).thenAnswer(invocation->rows.stream().filter(m->m.id()<=(long)invocation.getArgument(2)).sorted(Comparator.comparingLong(ConversationMapper.Message::id).reversed()).limit(20).toList());
            when(mapper.latestUser(ID,CUSTOMER)).thenAnswer(invocation->latestOverride.get()!=null?latestOverride.get():rows.stream().filter(m->"USER".equals(m.role())).max(Comparator.comparingLong(ConversationMapper.Message::id)).orElse(null));
            when(mapper.dialogueState(ID,CUSTOMER,ORDER)).thenAnswer(invocation->state.get());when(mapper.dialogueNow()).thenAnswer(invocation->clock.get());
            when(mapper.draftAssociations(ID,CUSTOMER)).thenAnswer(invocation->associations.get());
            when(mapper.insertMessage(eq(ID),eq(CUSTOMER),anyString(),anyString(),anyString(),nullable(String.class))).thenAnswer(invocation->{
                transactions.id();rows.add(new ConversationMapper.Message(ids.incrementAndGet(),invocation.getArgument(2),invocation.getArgument(3),invocation.getArgument(4),invocation.getArgument(5),clock.get()));return 1;
            });
            when(mapper.updateDialogueState(any(),anyLong())).thenAnswer(invocation->{
                long transaction=transactions.id();var failure=updateFailure.getAndSet(null);if(failure!=null)throw failure;
                DialogueState proposed=invocation.getArgument(0);long expected=invocation.getArgument(1);var actual=state.get();
                if(rejectUpdate.get()||actual==null||actual.revision()!=expected)return 0;
                var latest=rows.stream().filter(m->"USER".equals(m.role())).max(Comparator.comparingLong(ConversationMapper.Message::id)).orElseThrow();
                if(!proposed.lastTurn().equals(latest.turnId()))return 0;
                state.set(proposed);stateWrites.add(new StateWrite(proposed,transaction));
                var afterEffect=effectThenFailure.getAndSet(null);if(afterEffect!=null)throw afterEffect;
                return 1;
            });
            when(mapper.complete(eq(ID),eq(CUSTOMER),anyString(),anyString(),anyString())).thenAnswer(invocation->{
                long transaction=transactions.id();String turn=invocation.getArgument(2),content=invocation.getArgument(3),json=invocation.getArgument(4);
                for(int i=0;i<rows.size();i++){var old=rows.get(i);if(old.turnId().equals(turn)&&"ASSISTANT".equals(old.role())){
                    rows.set(i,new ConversationMapper.Message(old.id(),old.turnId(),old.role(),content,json,old.createdAt()));
                    checkpoints.add(new Checkpoint(JSON.readTree(json).path("status").asString(),state.get(),transaction));return 1;
                }}return 0;
            });
            service=new AssistantConversationService(mapper,new ConversationService(mapper,orders),orders,drafts,retrieval,config,transactions,tickets,execution,ticketMapper);
            ReflectionTestUtils.setField(service,"observationSemantics",true);
        }
        void onlyExtraMissing(){state.set(new DialogueState(ID,CUSTOMER,ORDER,"COLLECTING","反复卡纸",null,"小王","123","extra",AUTH,NOW.plusMinutes(10),7,AUTH));}
        void appendUser(String turn,String content){rows.add(new ConversationMapper.Message(ids.incrementAndGet(),turn,"USER",content,null,clock.get()));}
        ConversationResponse.Turn submit(String input){return submit(UUID.randomUUID().toString(),input);}
        ConversationResponse.Turn submit(String turn,String input){return service.submit(CUSTOMER,ID,turn,input);}
        void oneClassificationOf(String input){
            assertNull(protocolFailure.get(),()->"Protocol fixture failed: "+protocolFailure.get());assertEquals(1,requests.size());
            var request=requests.get(0);assertTrue(request.path("tools").isMissingNode()||request.path("tools").isEmpty());
            assertEquals(2,request.path("messages").size());assertEquals("user",request.path("messages").get(1).path("role").asString());
            assertEquals(input,request.path("messages").get(1).path("content").asString());
            assertFalse(request.toString().contains(ID));assertFalse(request.toString().contains(AUTH));assertFalse(request.toString().contains("小王"));
        }
        void noBusinessTools(){verifyNoInteractions(retrieval,drafts,tickets);}
        @Override public void close(){model.stop(0);execution.close();}
    }
}

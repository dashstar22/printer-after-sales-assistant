package local.printer.assistant;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import local.printer.common.ApiResponses;
import local.printer.config.DependencyProperties;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class AssistantAuditTest {
    final Logger logger=(Logger)LoggerFactory.getLogger(AssistantAudit.class);
    final ListAppender<ILoggingEvent> appender=new ListAppender<>();
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void capture(){appender.start();logger.addAppender(appender);}
    @AfterEach void cleanup(){AssistantAudit.clear();logger.detachAppender(appender);appender.stop();}
    JsonNode row(int index){return json.readTree(appender.list.get(index).getFormattedMessage().split("ASSISTANT_AUDIT ",2)[1]);}
    ChatResponse response(TokenUsage usage){return ChatResponse.builder().aiMessage(AiMessage.from("PRIVATE_MODEL_T030")).tokenUsage(usage).build();}
    @Test void missingUsageAndFailedAttemptRemainUnknownRatherThanZero(){
        var run=AssistantAudit.open(UUID.randomUUID().toString(),"MESSAGE","PROTOCOL_SIMULATION");
        AssistantAudit.model(response(null),null,AssistantAudit.clock());
        AssistantAudit.model(null,new IllegalStateException("PRIVATE_EXCEPTION_T030"),AssistantAudit.clock());run.finish(201,null,false);
        assertEquals("UNAVAILABLE",row(0).get("usageStatus").asString());assertTrue(row(0).get("inputTokens").isNull());
        assertEquals("MODEL_UNAVAILABLE",row(1).get("status").asString());assertEquals("UNAVAILABLE",row(2).get("usageStatus").asString());
        assertTrue(row(2).get("observedTotalTokens").isNull());assertTrue(row(2).get("cost").isNull());
        assertTrue(appender.list.stream().noneMatch(e->e.getFormattedMessage().contains("PRIVATE_")));
    }
    @Test void reportedUsageAndPartialAttemptTotalsAreLabelledHonestly(){
        var run=AssistantAudit.open(UUID.randomUUID().toString(),"MESSAGE","PROTOCOL_SIMULATION");
        AssistantAudit.model(response(new TokenUsage(7,3,10)),null,AssistantAudit.clock());
        AssistantAudit.model(null,new IllegalStateException(),AssistantAudit.clock());run.finish(201,null,false);
        assertEquals(10,row(0).get("totalTokens").asInt());assertEquals("PARTIAL",row(2).get("usageStatus").asString());
        assertEquals(10,row(2).get("observedTotalTokens").asInt());assertEquals(2,row(2).get("modelAttempts").asInt());
    }
    @Test void invalidNegativeMetadataIsNotReportedAsMeasuredUsage(){
        var run=AssistantAudit.open(UUID.randomUUID().toString(),"MESSAGE","PROTOCOL_SIMULATION");
        AssistantAudit.model(response(new TokenUsage(-1,null,-3)),null,AssistantAudit.clock());run.finish(201,null,false);
        assertTrue(row(0).get("inputTokens").isNull());assertTrue(row(0).get("totalTokens").isNull());
        assertEquals("UNAVAILABLE",row(1).get("usageStatus").asString());
    }
    @Test void hostileToolNameAndStatusCannotInjectLogFieldsOrLines(){
        var run=AssistantAudit.open(UUID.randomUUID().toString(),"MESSAGE","PROTOCOL_SIMULATION");
        AssistantAudit.tool("PRIVATE_TOOL\nSUCCEEDED","PRIVATE_STATUS",AssistantAudit.clock(),"CHAT_TOOL");run.finish(201,null,false);
        assertEquals("UNREGISTERED",row(0).get("tool").asString());assertEquals("UNCLASSIFIED",row(0).get("status").asString());
        assertFalse(appender.list.get(0).getFormattedMessage().contains("PRIVATE"));
    }
    DependencyProperties config(boolean enabled,String url){return new DependencyProperties(new DependencyProperties.Qdrant(URI.create("http://127.0.0.1:9")),new DependencyProperties.Ai(enabled,URI.create(url),"fixture","PRIVATE_KEY"));}
    @Test void filterUsesServerTraceAndClearsThreadEvenOnFailure() throws Exception {
        var filter=new AssistantTraceFilter(config(true,"http://127.0.0.1:9/v1"),new MockEnvironment());
        var request=new MockHttpServletRequest("POST","/api/conversations/x/messages");request.addHeader("X-Trace-Id","PRIVATE_FAKE_TRACE");
        var response=new MockHttpServletResponse();
        assertThrows(IllegalStateException.class,()->filter.doFilter(request,response,(req,res)->{throw new IllegalStateException("PRIVATE_THROWN");}));
        assertNull(AssistantAudit.current());String id=response.getHeader("X-Trace-Id");UUID.fromString(id);
        assertEquals(ApiResponses.traceId(request),id);assertEquals(id,row(0).get("traceId").asString());assertEquals("HTTP_FAILED",row(0).get("status").asString());
    }
    @Test void modeDoesNotCertifyProviderAndExplicitSimulationOnlyAppliesToLocalFixture(){
        assertEquals("LOCAL_API_UNVERIFIED",AssistantTraceFilter.modelMode(config(true,"http://127.0.0.1:9/v1").ai(),"AUTO"));
        assertEquals("PROTOCOL_SIMULATION",AssistantTraceFilter.modelMode(config(true,"http://127.0.0.1:9/v1").ai(),"PROTOCOL_SIMULATION"));
        assertEquals("REMOTE_API_UNVERIFIED",AssistantTraceFilter.modelMode(config(true,"https://example.com/v1").ai(),"PROTOCOL_SIMULATION"));
        assertEquals("DISABLED",AssistantTraceFilter.modelMode(config(false,"https://example.com/v1").ai(),"AUTO"));
    }
    @Test void independentThreadsDoNotShareAuditContext() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            var barrier=new CyclicBarrier(2);var ids=List.of(UUID.randomUUID().toString(),UUID.randomUUID().toString());
            var futures=new ArrayList<Future<String>>();
            for(String id:ids)futures.add(pool.submit(()->{var run=AssistantAudit.open(id,"MESSAGE","PROTOCOL_SIMULATION");try{barrier.await();return AssistantAudit.current()==run?id:"wrong";}finally{AssistantAudit.clear();}}));
            for(int i=0;i<2;i++)assertEquals(ids.get(i),futures.get(i).get(2,TimeUnit.SECONDS));assertNull(AssistantAudit.current());
        }finally{pool.shutdownNow();}
    }
}

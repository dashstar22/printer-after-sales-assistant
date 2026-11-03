package local.printer.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.printer.operations.ControlledDispatchGate;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class ControlledNotificationDispatchTest {
    HttpServer server;AtomicInteger calls;ControlledDispatchGate gate;NotificationDeliveries deliveries;
    ControlledNotificationDispatch sender;IntegrationCredentials.Identity actor;String destination;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup()throws Exception{
        calls=new AtomicInteger();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        destination="http://127.0.0.1:"+server.getAddress().getPort()+"/notify";
        gate=mock(ControlledDispatchGate.class);deliveries=mock(NotificationDeliveries.class);
        var queries=mock(AutomationQueries.class);var settings=mock(AutomationSettings.class);
        actor=new IntegrationCredentials.Identity("local-test",Set.of("notification:write"));
        when(deliveries.authorizedClaim(eq(actor),eq("claim"),eq("token"))).thenReturn(Map.of("event_key","summary","business_date","2026-11-01","target_id","local-target"));
        when(gate.notificationStep("summary")).thenReturn(json.valueToTree(Map.of("purpose","summary:2026-11-01","recipient","local-target","destination",destination)));
        when(gate.lab()).thenReturn(true);when(gate.instance()).thenReturn("A");
        when(gate.reserve(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new ControlledDispatchGate.Permit("attempt","summary"));
        when(queries.summary(anyMap())).thenReturn(Map.of("counts",Map.of("PENDING",1)));
        when(settings.ticketLink()).thenReturn("https://localhost:9443/app/");
        when(deliveries.result(eq(actor),eq("claim"),any())).thenAnswer(i->Map.of("status",((tools.jackson.databind.JsonNode)i.getArgument(2)).path("status").asString()));
        sender=new ControlledNotificationDispatch(gate,deliveries,queries,settings,mock(JdbcTemplate.class));
    }
    @AfterEach void stop(){server.stop(0);}
    void reply(int status,String body){
        server.createContext("/",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Location",destination+"-redirected");byte[] b=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,b.length);exchange.getResponseBody().write(b);exchange.close();});server.start();
    }
    Map<String,Object> send(){return sender.send(actor,"claim",json.valueToTree(Map.of("claimToken","token","stepId","summary")));}
    @Test void channelAcceptanceStillRequiresReceiptReview(){reply(200,"{\"code\":0}");var r=send();assertEquals("SENT",r.get("status"));assertEquals(true,r.get("requiresReceiptReview"));assertEquals(1,calls.get());verify(gate).finish(any(),eq("SENT"));}
    @Test void redirectIsNotFollowedOrRetried(){reply(302,"{}");assertEquals("UNKNOWN",send().get("status"));assertEquals(1,calls.get());verify(gate).finish(any(),eq("UNKNOWN"));}
    @Test void oversizedReceiptIsUnknownAndNotRetried(){reply(200,"x".repeat(9000));assertEquals("UNKNOWN",send().get("status"));assertEquals(1,calls.get());}
    @Test void invalidReceiptIsUnknownAndClientRejectionIsFailed(){reply(200,"not-json");assertEquals("UNKNOWN",send().get("status"));assertEquals(1,calls.get());}
    @Test void clientRejectionIsRecordedWithoutRetry(){reply(403,"{}");assertEquals("FAILED",send().get("status"));assertEquals(1,calls.get());}
    @Test void durableReservationFailurePreventsNetworkIO(){reply(200,"{\"code\":0}");when(gate.reserve(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenThrow(new ControlledDispatchGate.Denied());assertThrows(ControlledDispatchGate.Denied.class,this::send);assertEquals(0,calls.get());verify(deliveries,never()).result(any(),anyString(),any());}
}

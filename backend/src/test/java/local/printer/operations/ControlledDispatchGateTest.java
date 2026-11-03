package local.printer.operations;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class ControlledDispatchGateTest {
    @TempDir Path folder;Path policy,ledger;MockEnvironment env;Map<String,Object> body;List<Map<String,Object>> steps;
    static final String BINDING=ControlledDispatchGate.hash("test-smtp"),INPUT=ControlledDispatchGate.hash("approved-message");
    @BeforeEach void setup()throws Exception{
        policy=folder.resolve("policy.json");ledger=folder.resolve("attempts.jsonl");
        env=new MockEnvironment().withProperty("CONTROLLED_DISPATCH_REQUIRED","true").withProperty("CONTROLLED_DISPATCH_POLICY",policy.toString()).withProperty("CONTROLLED_DISPATCH_LEDGER",ledger.toString()).withProperty("CONTROLLED_DISPATCH_INSTANCE","A");
        var g=new ControlledDispatchGate(env);steps=new ArrayList<>();steps.add(step("one","A","SMTP","BIND","a@dispatch.invalid"));steps.add(step("two","B","SMTP","BIND","b@dispatch.invalid"));
        body=new LinkedHashMap<>(Map.of("version",1,"batchId","unit-batch","mode","LOCAL_TEST","status","APPROVED","approvalReference","synthetic-fixture","notBefore",Instant.now().minusSeconds(30).toString(),"expiresAt",Instant.now().plusSeconds(3600).toString(),"instances",Map.of("A",g.instanceBinding(),"B",g.instanceBinding()),"limits",Map.of("SMTP",8,"NOTIFY",2),"steps",steps));write(true);
    }
    Map<String,Object> step(String id,String instance,String kind,String purpose,String recipient){var m=new LinkedHashMap<String,Object>(Map.of("id",id,"instance",instance,"kind",kind,"purpose",purpose,"recipient",recipient,"binding",BINDING,"maxAttempts",1));if(kind.equals("NOTIFY"))m.put("inputSha256",INPUT);return m;}
    void write(boolean initialize)throws Exception{
        Files.writeString(policy,JsonMapper.builder().build().writeValueAsString(body));String hash=ControlledDispatchGate.hash(Files.readAllBytes(policy));env.setProperty("CONTROLLED_DISPATCH_POLICY_SHA256",hash);
        if(initialize)Files.writeString(ledger,"{\"type\":\"BATCH\",\"policySha256\":\""+hash+"\"}\n");
    }
    ControlledDispatchGate gate(){return new ControlledDispatchGate(env);}
    ControlledDispatchGate.Permit reserve(ControlledDispatchGate g){return g.reserve("SMTP","BIND",g.instance().equals("A")?"a@dispatch.invalid":"b@dispatch.invalid",BINDING,INPUT,g.instance()+":send:1");}
    @Test void disabledCannotGrantButShowsDisabled(){var g=new ControlledDispatchGate(new MockEnvironment());assertEquals(Map.of("enabled",false),g.status());assertThrows(ControlledDispatchGate.Denied.class,g::ready);}
    @Test void draftAndTamperedPolicyCannotGrant()throws Exception{body.put("status","DRAFT");write(true);assertThrows(ControlledDispatchGate.Denied.class,()->reserve(gate()));body.put("status","APPROVED");write(true);Files.writeString(policy," ",StandardOpenOption.APPEND);assertThrows(ControlledDispatchGate.Denied.class,()->reserve(gate()));}
    @Test void missingOrTruncatedLedgerFailsClosed()throws Exception{Files.delete(ledger);assertThrows(ControlledDispatchGate.Denied.class,()->reserve(gate()));assertFalse(Files.exists(ledger));Files.writeString(ledger,"{\"type\":\"BATCH\"");assertThrows(ControlledDispatchGate.Denied.class,()->reserve(gate()));}
    @Test void successRequiresReceiptBeforeOtherCopyCanSend(){var a=gate();var p=reserve(a);a.finish(p,"SMTP_ACCEPTED");env.setProperty("CONTROLLED_DISPATCH_INSTANCE","B");var b=gate();assertThrows(ControlledDispatchGate.Denied.class,()->reserve(b));b.review(p.attemptId(),"synthetic-inbox-checked","test-operator");assertNotNull(reserve(b));}
    @Test void failedOutcomeHaltsWholeBatch(){var g=gate();var p=reserve(g);g.finish(p,"FAILED");assertTrue((Boolean)g.status().get("stopped"));assertThrows(ControlledDispatchGate.Denied.class,()->g.review(p.attemptId(),"test","test"));}
    @Test void unknownOutcomeIsNeverRefunded(){var g=gate();g.finish(reserve(g),"UNKNOWN");assertEquals(Map.of("SMTP",1),g.status().get("counts"));assertThrows(ControlledDispatchGate.Denied.class,()->reserve(gate()));}
    @Test void pendingAfterRestartBlocksFurtherIO(){var p=reserve(gate());var restarted=gate();assertEquals(p.attemptId(),restarted.status().get("pending"));assertThrows(ControlledDispatchGate.Denied.class,()->reserve(restarted));}
    @Test void exactRecipientAndBindingAreRequired(){var g=gate();for(String address:List.of("a+tag@dispatch.invalid","other@dispatch.invalid"))assertThrows(ControlledDispatchGate.Denied.class,()->g.reserve("SMTP","BIND",address,BINDING,INPUT,"key"));assertThrows(ControlledDispatchGate.Denied.class,()->g.reserve("SMTP","BIND","a@dispatch.invalid",INPUT,INPUT,"key"));assertEquals(Map.of(),g.status().get("counts"));}
    @Test void stepCannotBeRepeatedEvenWithNewRequestKey(){var g=gate();var p=reserve(g);g.finish(p,"SMTP_ACCEPTED");g.review(p.attemptId(),"test-inbox","test");assertThrows(ControlledDispatchGate.Denied.class,()->g.reserve("SMTP","BIND","a@dispatch.invalid",BINDING,INPUT,"new-key"));}
    @Test void sharedTotalSurvivesCopySwitch()throws Exception{body.put("limits",Map.of("SMTP",1,"NOTIFY",2));write(true);var g=gate();var p=reserve(g);g.finish(p,"SMTP_ACCEPTED");g.review(p.attemptId(),"test","test");env.setProperty("CONTROLLED_DISPATCH_INSTANCE","B");assertThrows(ControlledDispatchGate.Denied.class,()->reserve(gate()));}
    @Test void notificationBodyMustMatchApprovedHash()throws Exception{steps.clear();steps.add(step("notify","A","NOTIFY","summary:2026-11-01","test-target"));write(true);assertThrows(ControlledDispatchGate.Denied.class,()->gate().reserve("NOTIFY","summary:2026-11-01","test-target",BINDING,ControlledDispatchGate.hash("extra-secret-field"),"notify1"));assertNotNull(gate().reserve("NOTIFY","summary:2026-11-01","test-target",BINDING,INPUT,"notify1"));}
    @Test void changedScopeCannotReuseOrResetLedger()throws Exception{reserve(gate());body.put("batchId","another-batch");write(false);assertThrows(ControlledDispatchGate.Denied.class,()->reserve(gate()));}
    @Test void expiredOrDifferentCopyFailsClosed()throws Exception{env.setProperty("CONTROLLED_DISPATCH_INSTANCE","unapproved");assertThrows(ControlledDispatchGate.Denied.class,()->reserve(gate()));env.setProperty("CONTROLLED_DISPATCH_INSTANCE","A");body.put("expiresAt",Instant.now().minusSeconds(1).toString());write(true);assertThrows(ControlledDispatchGate.Denied.class,()->reserve(gate()));}
    @Test void labModeCannotReachRealTransport(){var g=gate();assertThrows(ControlledDispatchGate.Denied.class,()->g.transport("SMTP","smtp.example.org",true));assertThrows(ControlledDispatchGate.Denied.class,()->g.transport("NOTIFY","open.feishu.cn",true));assertDoesNotThrow(()->g.transport("SMTP","127.0.0.1",false));}
    @Test void limitsCannotBeRaisedBeyondTaskScope()throws Exception{body.put("limits",Map.of("SMTP",9,"NOTIFY",2));write(true);assertThrows(ControlledDispatchGate.Denied.class,()->reserve(gate()));}
    @Test void concurrentCopiesHaveOnlyOneInflightAttempt()throws Exception{
        var pool=Executors.newFixedThreadPool(12);try{var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<24;i++)futures.add(pool.submit(()->{try{reserve(gate());return true;}catch(ControlledDispatchGate.Denied denied){return false;}}));int permitted=0;for(var f:futures)if(f.get(5,TimeUnit.SECONDS))permitted++;assertEquals(1,permitted);}finally{pool.shutdownNow();}
    }
    @Test void separateProcessCrashLeavesReservation()throws Exception{
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,GateProcessProbe.class.getName(),policy.toString(),ledger.toString(),env.getProperty("CONTROLLED_DISPATCH_POLICY_SHA256"));
        var p=command.redirectErrorStream(true).start();assertTrue(p.waitFor(15,TimeUnit.SECONDS));assertEquals(0,p.exitValue(),new String(p.getInputStream().readAllBytes()));assertThrows(ControlledDispatchGate.Denied.class,()->reserve(gate()));
    }
    @Test void samePurposeCooldownIsSharedAcrossCopies()throws Exception{
        steps.set(1,step("two","B","SMTP","BIND","a@dispatch.invalid"));write(true);
        var a=gate();var p=reserve(a);a.finish(p,"SMTP_ACCEPTED");a.review(p.attemptId(),"local-capture","tester");
        env.setProperty("CONTROLLED_DISPATCH_INSTANCE","B");
        assertThrows(ControlledDispatchGate.Denied.class,()->gate().reserve("SMTP","BIND","a@dispatch.invalid",BINDING,INPUT,"B:second"));
        assertEquals(Map.of("SMTP",1),gate().status().get("counts"));
    }
    @Test void hourlyRecipientCapIncludesDifferentPurposesAndCopies()throws Exception{
        steps.clear();for(int i=0;i<6;i++)steps.add(step("mail"+i,"A","SMTP","BIND","a@dispatch.invalid"));write(true);
        // Seed prior durable usage at distinct minutes, outside the 60-second cooldown.
        var rows=new StringBuilder(Files.readString(ledger));var mapper=JsonMapper.builder().build();
        for(int i=0;i<5;i++){
            String id="attempt"+i,at=Instant.now().minusSeconds(300L+120L*i).toString();
            rows.append(mapper.writeValueAsString(Map.of("type","RESERVE","attemptId",id,"stepId","mail"+i,"kind","SMTP","key","key"+i,"recipientDigest",ControlledDispatchGate.hash("a@dispatch.invalid"),"purpose",i%2==0?"REGISTER":"RESET","at",at))).append('\n');
            rows.append(mapper.writeValueAsString(Map.of("type","FINISH","attemptId",id,"status","SMTP_ACCEPTED","at",at))).append('\n');
            rows.append(mapper.writeValueAsString(Map.of("type","REVIEW","attemptId",id,"at",at))).append('\n');
        }
        Files.writeString(ledger,rows);steps.clear();steps.add(step("mail5","A","SMTP","BIND","a@dispatch.invalid"));write(false);
        // Keep the original policy hash header in sync with this isolated synthetic fixture.
        var lines=Files.readString(ledger).lines().toList();Files.writeString(ledger,"{\"type\":\"BATCH\",\"policySha256\":\""+env.getProperty("CONTROLLED_DISPATCH_POLICY_SHA256")+"\"}\n"+String.join("\n",lines.subList(1,lines.size()))+"\n");
        assertThrows(ControlledDispatchGate.Denied.class,()->gate().reserve("SMTP","BIND","a@dispatch.invalid",BINDING,INPUT,"sixth"));
        assertEquals(Map.of("SMTP",5),gate().status().get("counts"));
    }
}

package local.printer.operations;

import java.net.URI;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in acceptance batch. Local shared filesystem only; never restore this journal with a business copy. */
@Service
public class ControlledDispatchGate {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Object JVM_LOCK=new Object();
    private final Environment env;
    public ControlledDispatchGate(Environment env){this.env=env;}
    public static final class Denied extends RuntimeException {public Denied(){super("CONTROLLED_DISPATCH_DENIED");}}
    public record Permit(String attemptId,String stepId){public String toString(){return "Permit[REDACTED]";}}
    public boolean required(){return env.getProperty("CONTROLLED_DISPATCH_REQUIRED",Boolean.class,false);}
    public static String hash(String text){return hash(text.getBytes(StandardCharsets.UTF_8));}
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new Denied();}}
    public String instance(){return env.getProperty("CONTROLLED_DISPATCH_INSTANCE","");}
    public String instanceBinding(){return hash(String.join("\n",env.getProperty("spring.datasource.url",""),env.getProperty("KNOWLEDGE_STORAGE_ROOT",""),env.getProperty("APP_TICKETS_PHOTO_ROOT",""),env.getProperty("CONTROLLED_DISPATCH_RESOURCE_ID","")));}
    private volatile String artifactHash;
    private String runtimeArtifact(){
        if(artifactHash==null)synchronized(JVM_LOCK){if(artifactHash==null)try{
            Path jar=Path.of(System.getProperty("java.class.path")).toAbsolutePath();
            if(!Files.isRegularFile(jar)||!jar.toString().endsWith(".jar"))throw new Denied();
            artifactHash=hash(Files.readAllBytes(jar));
        }catch(Exception e){throw new Denied();}}
        return artifactHash;
    }
    public boolean lab(){return policy().path("mode").asString().equals("LOCAL_TEST");}
    public void transport(String kind,String address,boolean secure){
        boolean loop=Set.of("localhost","127.0.0.1","::1","[::1]").contains(address);
        if(lab()&&!loop||!lab()&&(!secure||loop))throw new Denied();
    }
    private JsonNode policy(){
        try{
            if(!required())throw new Denied();
            Path p=Path.of(env.getRequiredProperty("CONTROLLED_DISPATCH_POLICY"));
            if(!p.isAbsolute()||Files.isSymbolicLink(p)||Files.size(p)>65536)throw new Denied();
            byte[] bytes=Files.readAllBytes(p);
            if(!hash(bytes).equals(env.getRequiredProperty("CONTROLLED_DISPATCH_POLICY_SHA256")))throw new Denied();
            var n=JSON.readTree(bytes);Instant now=Instant.now();
            if(n.path("version").asInt()!=1||!n.path("batchId").asString().matches("[A-Za-z0-9_-]{1,64}")||!n.path("status").asString().equals("APPROVED")
                ||n.path("approvalReference").asString().isBlank()||!Instant.parse(n.path("notBefore").asString()).isBefore(now)
                ||!Instant.parse(n.path("expiresAt").asString()).isAfter(now)
                ||Duration.between(Instant.parse(n.path("notBefore").asString()),Instant.parse(n.path("expiresAt").asString())).compareTo(Duration.ofHours(24))>0
                ||!n.path("instances").path(instance()).asString().equals(instanceBinding())||!n.path("steps").isArray()||n.path("steps").size()>10)throw new Denied();
            boolean lab=n.path("mode").asString().equals("LOCAL_TEST");
            if(!lab&&!n.path("mode").asString().equals("REAL"))throw new Denied();
            String artifact=env.getProperty("CONTROLLED_DISPATCH_ARTIFACT_SHA256","");
            if(!lab&&(!artifact.matches("[a-f0-9]{64}")||!artifact.equals(n.path("artifactSha256").asString())||!artifact.equals(runtimeArtifact())))throw new Denied();
            if(n.path("limits").path("SMTP").asInt(-1)<0||n.path("limits").path("SMTP").asInt()>8
                ||n.path("limits").path("NOTIFY").asInt(-1)<0||n.path("limits").path("NOTIFY").asInt()>2)throw new Denied();
            Set<String> ids=new HashSet<>();
            for(var step:n.path("steps")){
                if(!step.path("id").asString().matches("[A-Za-z0-9_-]{1,64}")||!ids.add(step.path("id").asString())||step.path("maxAttempts").asInt()!=1)throw new Denied();
                String kind=step.path("kind").asString(),recipient=step.path("recipient").asString();
                if(!Set.of("SMTP","NOTIFY").contains(kind)||!step.path("binding").asString().matches("[a-f0-9]{64}"))throw new Denied();
                if(kind.equals("SMTP")){
                    if(!Set.of("REGISTER","INVITE","BIND","RESET").contains(step.path("purpose").asString()))throw new Denied();
                    var email=local.printer.auth.email.EmailAddress.parse(recipient);
                    if(!email.normalized().equals(recipient)||lab&&!recipient.endsWith(".invalid"))throw new Denied();
                }else if(!step.path("inputSha256").asString().matches("[a-f0-9]{64}"))throw new Denied();
            }
            return n;
        }catch(Exception e){throw new Denied();}
    }
    public void ready(){policy();}
    public JsonNode notificationStep(String id){var p=policy();for(var s:p.path("steps"))if(s.path("id").asString().equals(id)&&s.path("kind").asString().equals("NOTIFY")&&s.path("instance").asString().equals(instance()))return s;throw new Denied();}
    public Permit reserve(String kind,String purpose,String recipient,String binding,String inputHash,String key){
        return locked(s->{
            if(s.stopped||s.pending!=null||s.awaiting!=null)throw new Denied();
            JsonNode selected=null;
            for(var step:s.policy.path("steps"))if(step.path("instance").asString().equals(instance())&&step.path("kind").asString().equals(kind)&&step.path("purpose").asString().equals(purpose)
                &&step.path("recipient").asString().equals(recipient)&&step.path("binding").asString().equals(binding)
                &&(kind.equals("SMTP")||step.path("inputSha256").asString().equals(inputHash))){if(selected!=null)throw new Denied();selected=step;}
            if(selected==null||s.usedSteps.contains(selected.path("id").asString())||s.keys.contains(hash(key))
                ||s.counts.getOrDefault(kind,0)>=s.policy.path("limits").path(kind).asInt())throw new Denied();
            if(kind.equals("SMTP")){
                String emailHash=hash(recipient);Instant now=Instant.now();long hour=0;
                for(var mail:s.mailUsage)if(mail.email.equals(emailHash)&&Duration.between(mail.at,now).compareTo(Duration.ofHours(1))<0){
                    hour++;if(mail.purpose.equals(purpose)&&Duration.between(mail.at,now).compareTo(Duration.ofSeconds(60))<0)throw new Denied();
                }
                if(hour>=5)throw new Denied();
            }
            String id=UUID.randomUUID().toString(),step=selected.path("id").asString();
            s.append(Map.of("type","RESERVE","attemptId",id,"stepId",step,"kind",kind,"key",hash(key),"inputSha256",inputHash,"recipientDigest",hash(recipient),"purpose",purpose));
            return new Permit(id,step);
        });
    }
    public void finish(Permit permit,String result){
        if(!Set.of("SMTP_ACCEPTED","SENT","FAILED","UNKNOWN").contains(result))throw new Denied();
        locked(s->{if(!permit.attemptId().equals(s.pending))throw new Denied();s.append(Map.of("type","FINISH","attemptId",permit.attemptId(),"status",result));return null;});
    }
    public Map<String,Object> status(){if(!required())return Map.of("enabled",false);return locked(s->Map.of("enabled",true,"batchId",s.policy.path("batchId").asString(),"counts",s.counts,"stopped",s.stopped,"pending",Objects.toString(s.pending,""),"awaitingReceipt",Objects.toString(s.awaiting,"")));}
    public void review(String attempt,String reference,String actor){
        if(reference==null||!reference.matches("[A-Za-z0-9:_ -]{1,120}")||actor==null||actor.isBlank())throw new Denied();
        locked(s->{if(s.stopped||!attempt.equals(s.awaiting))throw new Denied();s.append(Map.of("type","REVIEW","attemptId",attempt,"receiptReference",reference,"actor",actor));return null;});
    }
    public void stop(String actor){locked(s->{s.append(Map.of("type","STOP","actor",actor));return null;});}
    private <T>T locked(Function<State,T> work){
        synchronized(JVM_LOCK){
            try{
                var p=policy();Path file=Path.of(env.getRequiredProperty("CONTROLLED_DISPATCH_LEDGER"));
                if(!file.isAbsolute()||Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>1048576)throw new Denied();
                try(var channel=FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE);var lock=channel.tryLock()){
                    if(lock==null)throw new Denied();
                    ByteBuffer data=ByteBuffer.allocate((int)channel.size());while(data.hasRemaining())if(channel.read(data)<0)throw new Denied();
                    String text=new String(data.array(),StandardCharsets.UTF_8);if(!text.endsWith("\n"))throw new Denied();
                    var state=new State(p,channel);String[] lines=text.split("\n");
                    var header=JSON.readTree(lines[0]);if(!header.path("type").asString().equals("BATCH")||!header.path("policySha256").asString().equals(env.getRequiredProperty("CONTROLLED_DISPATCH_POLICY_SHA256")))throw new Denied();
                    for(int i=1;i<lines.length;i++)state.read(JSON.readTree(lines[i]));
                    return work.apply(state);
                }
            }catch(Exception e){throw new Denied();}
        }
    }
    private static final class State {
        final JsonNode policy;final FileChannel channel;final Set<String> usedSteps=new HashSet<>(),keys=new HashSet<>();final Map<String,Integer> counts=new HashMap<>();
        private record MailUsage(String email,String purpose,Instant at){}
        final List<MailUsage> mailUsage=new ArrayList<>();
        String pending,awaiting;boolean stopped;
        State(JsonNode p,FileChannel c){policy=p;channel=c;}
        void read(JsonNode n){
            String type=n.path("type").asString(),id=n.path("attemptId").asString();
            switch(type){
                case "RESERVE"->{if(pending!=null||awaiting!=null||stopped||!usedSteps.add(n.path("stepId").asString())||!keys.add(n.path("key").asString()))throw new Denied();pending=id;counts.merge(n.path("kind").asString(),1,Integer::sum);if(n.path("kind").asString().equals("SMTP"))mailUsage.add(new MailUsage(n.path("recipientDigest").asString(),n.path("purpose").asString(),Instant.parse(n.path("at").asString())));}
                case "FINISH"->{if(!id.equals(pending))throw new Denied();pending=null;if(Set.of("SMTP_ACCEPTED","SENT").contains(n.path("status").asString()))awaiting=id;else stopped=true;}
                case "REVIEW"->{if(!id.equals(awaiting)||stopped)throw new Denied();awaiting=null;}
                case "STOP"->stopped=true;
                default->throw new Denied();
            }
        }
        void append(Map<String,Object> fields){try{var row=new LinkedHashMap<>(fields);row.put("at",Instant.now().toString());byte[] b=(JSON.writeValueAsString(row)+"\n").getBytes(StandardCharsets.UTF_8);channel.position(channel.size());ByteBuffer buffer=ByteBuffer.wrap(b);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}catch(Exception e){throw new Denied();}}
    }
}

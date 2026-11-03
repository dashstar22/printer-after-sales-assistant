package local.printer.knowledge.index;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import local.printer.assistant.AssistantExecution;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, non-redirecting, single-attempt transport. Exceptions never contain URLs or supplier bodies. */
public final class IndexHttp {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public static URI endpoint(String value,boolean loopbackOnly){
        try{var uri=URI.create(value);String host=uri.getHost();boolean loop=Set.of("127.0.0.1","localhost","[::1]").contains(Objects.toString(host,""));
            if(host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                ||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme())&&loop)||loopbackOnly&&!loop)throw new IllegalArgumentException();
            return uri;
        }catch(RuntimeException e){throw new IllegalArgumentException("INDEX_ENDPOINT_INVALID");}
    }
    public static boolean loopback(URI uri){return Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost());}
    public static URI internalEndpoint(String value,org.springframework.core.env.Environment env){
        try{return endpoint(value,true);}catch(IllegalArgumentException rejected){
            try{var u=URI.create(value);var allowed=Set.of(env.getProperty("KNOWLEDGE_INTERNAL_HOSTS","").split(","));
                if(!allowed.contains(u.getHost())||u.getHost()==null||u.getHost().isBlank()||!"http".equals(u.getScheme())||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw rejected;
                return u;
            }catch(RuntimeException invalid){throw new IllegalArgumentException("INDEX_ENDPOINT_INVALID");}
        }
    }
    public JsonNode send(String method,URI uri,String key,Object body,long timeoutMs,String code,boolean supplier,boolean missingAllowed){
        try{
            Duration timeout=Duration.ofMillis(local.printer.operations.ExternalCallBudget.remainingMillis(timeoutMs));var scope=AssistantExecution.current();
            if(scope!=null&&scope.remaining().compareTo(timeout)<0)timeout=scope.remaining();
            var request=HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type","application/json");
            if(key!=null&&!key.isBlank()){
                if(key.contains("\r")||key.contains("\n"))throw new IllegalArgumentException();
                request.header("Authorization","Bearer "+key);
            }
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
            var future=http.sendAsync(request.build(),r->new Body());HttpResponse<byte[]> response;
            try{response=future.get(timeout.toMillis()+100,TimeUnit.MILLISECONDS);}catch(Exception e){future.cancel(true);throw e;}
            if(missingAllowed&&response.statusCode()==404)return null;
            if(response.statusCode()<200||response.statusCode()>=300)throw new KnowledgeIndexPipeline.IndexFailure(code,response.statusCode()>=400&&response.statusCode()<500&&response.statusCode()!=429,supplier);
            var json=JSON.readTree(response.body());if(json==null||!json.isObject())throw new IllegalArgumentException();return json;
        }catch(local.printer.operations.ExternalCallBudget.Limit e){throw e;}
        catch(KnowledgeIndexPipeline.IndexFailure e){throw e;}
        catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new KnowledgeIndexPipeline.IndexFailure(code,false,supplier);}
    }
    static final class Body implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){for(var b:buffers){if((long)bytes.size()+b.remaining()>4*1024*1024){subscription.cancel();result.completeExceptionally(new IOException());return;}byte[] next=new byte[b.remaining()];b.get(next);bytes.writeBytes(next);}subscription.request(1);}
        public void onError(Throwable e){result.completeExceptionally(new IOException());}public void onComplete(){result.complete(bytes.toByteArray());}
    }
}

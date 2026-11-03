package local.printer.auth.email;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

/** Single-instance fallback; bounded memory, fail closed on exhausted key capacity. Ignore forwarded IP. */
@Component
public class EmailRateLimits {
    private record Window(ArrayDeque<Long> times){}
    private final Map<String,Window> windows=new HashMap<>();
    private final Clock clock;
    private local.printer.operations.RedisProtection shared;
    @org.springframework.beans.factory.annotation.Autowired
    public void shared(local.printer.operations.RedisProtection value){shared=value;}
    public EmailRateLimits(){this(Clock.systemUTC());}
    EmailRateLimits(Clock clock){this.clock=clock;}
    public synchronized void request(String ip){check("request:"+ip,20,3600000,true);if(shared!=null&&!shared.allow("email-ip",ip,20,java.time.Duration.ofHours(1)))throw limited();}
    public synchronized void consumption(String ip){check("failure:"+ip,10,900000,false);}
    public synchronized void failed(String ip){check("failure:"+ip,10,900000,true);if(shared!=null&&!shared.allow("email-failure",ip,10,java.time.Duration.ofMinutes(15)))throw limited();}
    private void check(String key,int max,long duration,boolean add) {
        long now=clock.millis();
        // Both windows are at most an hour. Prune without an unbounded scheduling/queue dependency.
        windows.entrySet().removeIf(e->e.getValue().times().isEmpty() || e.getValue().times().peekLast()<=now-3600000);
        Window w=windows.get(key);
        if(w==null){if(windows.size()>=10000)throw limited();w=new Window(new ArrayDeque<>());windows.put(key,w);}
        while(!w.times().isEmpty() && w.times().peekFirst()<=now-duration)w.times().removeFirst();
        if(w.times().size()>=max)throw limited();
        if(add)w.times().addLast(now);
    }
    private EmailFailure limited(){return new EmailFailure(429,"EMAIL_RATE_LIMITED","请求过于频繁，请稍后重试");}
}

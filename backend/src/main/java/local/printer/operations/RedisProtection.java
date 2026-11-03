package local.printer.operations;

import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Lua is atomic; the local sliding bound is charged on every attempt, including shared-store failures. */
@Component
public class RedisProtection {
    private final StringRedisTemplate redis;private final Environment env;
    private record Window(long duration,ArrayDeque<Long> times){}
    private final Map<String,Window> windows=new HashMap<>();
    private volatile String state="DISABLED";private long lastWarning;
    private static final DefaultRedisScript<Long> LIMIT=new DefaultRedisScript<>("""
        local n=redis.call('INCR',KEYS[1])
        if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end
        return n
        """,Long.class);
    public RedisProtection(StringRedisTemplate redis,Environment env){this.redis=redis;this.env=env;}
    public boolean enabled(){return env.getProperty("app.redis.enabled",Boolean.class,false);}
    public String state(){return state;}
    public boolean allow(String category,String identity,int max,Duration duration){
        if(max<1||max>100000||duration.isNegative()||duration.isZero()||duration.compareTo(Duration.ofDays(1))>0)throw new IllegalArgumentException("RATE_CONFIG_INVALID");
        String key=key("limit",env.getProperty("REDIS_NAMESPACE","single-enterprise"),category,identity);long millis=duration.toMillis();
        if(!local(key,max,millis))return false;
        if(!enabled())return true;
        try{Long n=redis.execute(LIMIT,List.of(key),Long.toString(millis));if(n==null)throw new IllegalStateException();state="READY";return n<=max;}
        catch(RuntimeException unavailable){failed();return true;} // Already charged against the same strict local upper bound.
    }
    private synchronized boolean local(String key,int max,long duration){
        long now=System.currentTimeMillis();windows.entrySet().removeIf(e->e.getValue().times().isEmpty()||e.getValue().times().peekLast()<=now-e.getValue().duration());
        var w=windows.get(key);if(w==null){if(windows.size()>=10000)return false;w=new Window(duration,new ArrayDeque<>());windows.put(key,w);}
        while(!w.times().isEmpty()&&w.times().peekFirst()<=now-duration)w.times().removeFirst();
        if(w.times().size()>=max)return false;w.times().addLast(now);return true;
    }
    public String get(String key){if(!enabled())return null;try{String value=redis.opsForValue().get(key);state="READY";return value;}catch(RuntimeException unavailable){failed();return null;}}
    public void put(String key,String value,Duration ttl){if(!enabled())return;try{redis.opsForValue().set(key,value,ttl);state="READY";}catch(RuntimeException unavailable){failed();}}
    private synchronized void failed(){state="LOCAL_FALLBACK";long now=System.currentTimeMillis();if(now-lastWarning>60000){lastWarning=now;LoggerFactory.getLogger(getClass()).warn("REDIS_UNAVAILABLE local bounded protection active; candidate cache bypassed");}}
    public static String key(String kind,String... parts){
        try{var digest=MessageDigest.getInstance("SHA-256");for(String part:parts){byte[] bytes=Objects.toString(part,"").getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}return "printer:v1:"+kind+":"+HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}

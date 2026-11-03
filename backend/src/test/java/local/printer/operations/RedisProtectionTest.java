package local.printer.operations;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

@EnabledIfEnvironmentVariable(named="PHASE5_REDIS_PORT",matches=".+")
class RedisProtectionTest {
    LettuceConnectionFactory connection;StringRedisTemplate redis;MockEnvironment env;
    @BeforeEach void connect(){
        var config=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(System.getenv("PHASE5_REDIS_PORT")));
        connection=new LettuceConnectionFactory(config,LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(250)).shutdownTimeout(Duration.ofMillis(50)).build());connection.afterPropertiesSet();connection.start();
        redis=new StringRedisTemplate(connection);env=new MockEnvironment().withProperty("app.redis.enabled","true");
    }
    @AfterEach void close(){connection.destroy();}
    @Test void atomicQuotaAcrossInstancesUnderConcurrencyAndHashesContainNoIdentity()throws Exception{
        var a=new RedisProtection(redis,env);var b=new RedisProtection(redis,env);String id="email-private-"+UUID.randomUUID();var pool=Executors.newFixedThreadPool(8);var count=new AtomicInteger();
        try{var jobs=new ArrayList<Callable<Void>>();for(int i=0;i<40;i++){var instance=i%2==0?a:b;jobs.add(()->{if(instance.allow("test",id,8,Duration.ofMinutes(1)))count.incrementAndGet();return null;});}for(var f:pool.invokeAll(jobs))f.get();}finally{pool.shutdownNow();}
        assertEquals(8,count.get());String key=RedisProtection.key("limit","single-enterprise","test",id);assertFalse(key.contains(id));assertTrue(redis.getExpire(key)>0&&redis.getExpire(key)<=60);
    }
    @Test void realDisconnectKeepsConsumedQuotaAndCacheBypassesWithoutLeaking()throws Exception{
        String container=System.getenv("PHASE5_REDIS_CONTAINER");assertTrue(container.startsWith("printer-p5-redis-"));
        var limits=new RedisProtection(redis,env);String id=UUID.randomUUID().toString();for(int i=0;i<3;i++)assertTrue(limits.allow("outage",id,5,Duration.ofMinutes(1)));
        assertEquals(0,new ProcessBuilder("docker","stop","-t","1",container).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor());
        try{assertTrue(limits.allow("outage",id,5,Duration.ofMinutes(1)));assertTrue(limits.allow("outage",id,5,Duration.ofMinutes(1)));assertFalse(limits.allow("outage",id,5,Duration.ofMinutes(1)));assertEquals("LOCAL_FALLBACK",limits.state());assertNull(limits.get(RedisProtection.key("candidates","test")));}
        finally{assertEquals(0,new ProcessBuilder("docker","start",container).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor());}
        assertFalse(limits.allow("outage",id,5,Duration.ofMinutes(1))); // Recovery cannot reset the local ledger either.
    }
}

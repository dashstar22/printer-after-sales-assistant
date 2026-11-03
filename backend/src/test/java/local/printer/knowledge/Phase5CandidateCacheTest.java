package local.printer.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import local.printer.operations.RedisProtection;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Inherits the real publication/filter/recovery regressions; suppliers remain synthetic loopback fixtures. */
@EnabledIfEnvironmentVariable(named="PHASE5_REDIS_PORT",matches=".+")
class Phase5CandidateCacheTest extends HybridKnowledgeDatabaseTest {
    LettuceConnectionFactory connection;StringRedisTemplate redis;KnowledgeCandidateCache cache;
    @BeforeEach void attachCache(TestInfo info){
        connection=new LettuceConnectionFactory("127.0.0.1",Integer.parseInt(System.getenv("PHASE5_REDIS_PORT")));connection.afterPropertiesSet();connection.start();redis=new StringRedisTemplate(connection);
        env.withProperty("app.redis.enabled","true");cache=new KnowledgeCandidateCache(new RedisProtection(redis,env),env);if(info.getTestMethod().orElseThrow().getDeclaringClass()==Phase5CandidateCacheTest.class)hybrid.cache(cache);
    }
    @AfterEach void closeCache(){if(connection!=null)connection.destroy();}
    @Test void hitOnlyReusesCandidatesAndRechecksGrantsReplacementAndDisable() {
        var a=source("P-100","警告：先断电再处理E-101卡纸。");publish(a);
        assertEquals("MISS",search("E-101").trace().get("cache"));int before=embeddings.get();
        var hit=search("E-101");assertEquals("HIT",hit.trace().get("cache"));assertEquals(before,embeddings.get());assertEquals(2,reranks.get());
        var keys=redis.keys("printer:v1:candidates:*");assertNotNull(keys);assertFalse(keys.isEmpty());
        for(String key:keys){assertTrue(redis.getExpire(key)>0&&redis.getExpire(key)<=300);String value=redis.opsForValue().get(key);assertFalse(value.contains("卡纸"));assertFalse(value.contains("警告"));}
        db.update("UPDATE knowledge_permissions SET allowed=FALSE WHERE document_id=? AND purpose='chat'",a.id());
        assertEquals("EVIDENCE_NOT_AUTHORIZED",search("E-101").status());
        db.update("UPDATE knowledge_permissions SET allowed=TRUE WHERE document_id=? AND purpose='chat'",a.id());
        var next=revision(a,"新版：先断电并冷却再处理E-101。","P-100");publish(next);
        assertEquals("v2",search("E-101").sources().get(0).version());pipeline.disable(next.id(),next.revision());assertEquals("NO_EVIDENCE",search("E-101").status());assertEquals("v1",hit.sources().get(0).version());
    }
    @Test void configurationKeysAndTtlAreIsolatedAndExpiredCandidatesRecompute()throws Exception{
        publish(source("P-100","警告：先断电再处理E-101卡纸。"));env.withProperty("KNOWLEDGE_CACHE_TTL_SECONDS","1");
        search("E-101");int before=embeddings.get();Thread.sleep(1150);assertEquals("MISS",search("E-101").trace().get("cache"));assertEquals(before+1,embeddings.get());
        String key=cache.key("P-100","E-101","epoch1","c1","e1","r1");
        assertNotEquals(key,cache.key("P-200","E-101","epoch1","c1","e1","r1"));assertNotEquals(key,cache.key("P-100","E-101","epoch2","c1","e1","r1"));assertNotEquals(key,cache.key("P-100","E-101","epoch1","c2","e1","r1"));
    }
}

package local.printer.knowledge;

import java.time.Duration;
import java.util.*;
import local.printer.knowledge.index.IndexQuery.Hit;
import local.printer.operations.RedisProtection;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Only candidate IDs, scores and binding hashes; no query, source bodies, or business data. */
@Component
public class KnowledgeCandidateCache {
    public record Lanes(List<Hit> vector,List<Hit> bm25){}
    private final RedisProtection redis;private final Environment env;private final JsonMapper json=JsonMapper.builder().build();
    public KnowledgeCandidateCache(RedisProtection redis,Environment env){this.redis=redis;this.env=env;}
    public String key(String model,String query,String fingerprint,String configuration,String... identities){
        var parts=new ArrayList<String>(List.of(env.getProperty("REDIS_NAMESPACE","single-enterprise"),"knowledge-chat-shared",model,local.printer.knowledge.index.IndexQuery.normalized(query),fingerprint,configuration));
        parts.addAll(Arrays.asList(identities));return RedisProtection.key("candidates",parts.toArray(String[]::new));
    }
    public Lanes get(String key){try{String raw=redis.get(key);if(raw==null||raw.length()>100000)return null;var value=json.readValue(raw,Lanes.class);
        if(value.vector()==null||value.bm25()==null||value.vector().size()>20||value.bm25().size()>20)return null;
        for(var hits:List.of(value.vector(),value.bm25()))for(var hit:hits)
            if(hit==null||hit.generation()==null||hit.chunkId()==null||hit.configuration()==null||hit.manifest()==null||hit.textHash()==null||!Double.isFinite(hit.score()))return null;
        return value;
    }catch(RuntimeException invalid){return null;}}
    public void put(String key,List<Hit> vector,List<Hit> bm25){
        if(vector.size()>20||bm25.size()>20)return;int seconds=env.getProperty("KNOWLEDGE_CACHE_TTL_SECONDS",Integer.class,300);
        if(seconds<1||seconds>300)throw new IllegalArgumentException("CACHE_CONFIG_INVALID");
        redis.put(key,json.writeValueAsString(new Lanes(List.copyOf(vector),List.copyOf(bm25))),Duration.ofSeconds(seconds));
    }
}

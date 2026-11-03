package local.printer.knowledge;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ReciprocalRankFuser;
import java.nio.charset.StandardCharsets;
import java.util.*;
import local.printer.knowledge.index.IndexQuery.Hit;

/** RRF scores describe ranking only. Full original scores and ranks are retained independently. */
public final class HybridRanking {
    private HybridRanking() {}
    public record Ranked(String chunkId,String generation,Integer vectorRank,Double vectorScore,Integer bm25Rank,Double bm25Score,double fusionScore,Double rerankScore) {
        public Ranked reranked(double value){return new Ranked(chunkId,generation,vectorRank,vectorScore,bm25Rank,bm25Score,fusionScore,value);}
    }
    public static List<Ranked> fuse(List<Hit> vector,List<Hit> bm25){
        var rows=new TreeMap<String,Ranked>();var lists=new ArrayList<List<Content>>();
        for(int lane=0;lane<2;lane++){
            var distinct=new LinkedHashMap<String,Hit>();for(var hit:lane==0?vector:bm25)distinct.putIfAbsent(hit.chunkId(),hit);
            var content=new ArrayList<Content>();int rank=0;
            for(var hit:distinct.values()){
                rank++;var old=rows.get(hit.chunkId());if(old!=null&&!old.generation().equals(hit.generation()))throw new IllegalStateException("CHUNK_GENERATION_CONFLICT");
                Integer vectorRank=lane==0?Integer.valueOf(rank):old==null?null:old.vectorRank();
                Integer bm25Rank=lane==1?Integer.valueOf(rank):old==null?null:old.bm25Rank();
                Double vectorScore=lane==0?Double.valueOf(hit.score()):old==null?null:old.vectorScore();
                Double bm25Score=lane==1?Double.valueOf(hit.score()):old==null?null:old.bm25Score();
                rows.put(hit.chunkId(),new Ranked(hit.chunkId(),hit.generation(),vectorRank,vectorScore,bm25Rank,bm25Score,(old==null?0:old.fusionScore())+1.0/(60+rank),null));
                // Content equality includes stable identity; identical body text in different chunks never collapses identities.
                content.add(Content.from(TextSegment.from(hit.chunkId(),Metadata.from("stable_chunk_id",hit.chunkId()))));
            }
            lists.add(content);
        }
        var fused=ReciprocalRankFuser.fuse(lists,60);
        var result=fused.stream().map(c->rows.get(c.textSegment().text())).toList();
        return result.stream().sorted(Comparator.comparingDouble(Ranked::fusionScore).reversed().thenComparing(Ranked::chunkId)).toList();
    }
    /** UTF-8 byte count is a conservative upper bound for the configured byte/BPE services, not a measured token count. */
    public static int tokenUpperBound(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
    public record Unit(String chunkId,String title,String location,String text,String modelContext,String provenanceKey) {
        public Unit(String chunkId,String title,String location,String text){this(chunkId,title,location,text,null,title+"\0"+location);}
        public Unit(String chunkId,String title,String location,String text,String modelContext){this(chunkId,title,location,text,modelContext,title+"\0"+location);}
        public String context(){return modelContext==null?"["+chunkId+"] "+title+" "+location+"\n"+text:modelContext;}
    }
    public record Context(List<Unit> units,int tokenUpperBound,boolean limited,Map<String,String> duplicateOf) {}
    public static Context context(List<Unit> ranked,int budget){
        var selected=new ArrayList<Unit>();var duplicates=new LinkedHashMap<String,String>();int tokens=0;boolean limited=false;
        for(var unit:ranked){
            // Only whole, fully covered original text is removed. Partial overlap retains both complete units/conditions.
            var duplicate=selected.stream().filter(s->Objects.equals(s.provenanceKey(),unit.provenanceKey())&&s.text().contains(unit.text())).findFirst();
            if(duplicate.isPresent()){duplicates.put(unit.chunkId(),duplicate.get().chunkId());continue;}
            int size=tokenUpperBound(unit.context());
            if(selected.size()>=5||tokens+size>budget){limited=true;continue;}
            selected.add(unit);tokens+=size;
        }
        return new Context(List.copyOf(selected),tokens,limited,Map.copyOf(duplicates));
    }
}

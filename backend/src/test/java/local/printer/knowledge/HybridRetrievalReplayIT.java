package local.printer.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.StreamSupport;
import local.printer.knowledge.index.*;
import local.printer.knowledge.index.KnowledgeIndexAdapters.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit, offline DEV-only diagnosis. Reuses historical vector scores; never claims a fresh model run. */
class HybridRetrievalReplayIT {
    @TempDir Path localIndex;
    private final JsonMapper json=JsonMapper.builder().build();
    private JsonNode read(Path path)throws Exception{return json.readTree(Files.readString(path));}
    private List<JsonNode> lines(Path path)throws Exception{
        var result=new ArrayList<JsonNode>();for(String line:Files.readAllLines(path))if(!line.isBlank())result.add(json.readTree(line));return result;
    }
    private List<JsonNode> nodes(JsonNode node){return StreamSupport.stream(node.spliterator(),false).toList();}
    private List<String> strings(JsonNode node){return nodes(node).stream().map(JsonNode::asText).toList();}
    private Map<String,Object> score(JsonNode gold,List<String> ids){
        var units=nodes(gold.path("binding").path("semantic_units"));var found=new HashSet<>(ids.subList(0,Math.min(5,ids.size())));
        int covered=0;var relevant=new HashSet<String>();
        for(var unit:units){boolean complete=false;for(var alternative:unit.path("alternatives")){
            var required=strings(alternative.path("chunk_ids"));relevant.addAll(required);if(found.containsAll(required))complete=true;
        }if(complete)covered++;}
        double reciprocal=0;for(int i=0;i<Math.min(5,ids.size());i++)if(relevant.contains(ids.get(i))){reciprocal=1.0/(i+1);break;}
        return Map.of("recallAt5",units.isEmpty()?0.0:(double)covered/units.size(),"mrrAt5",reciprocal,"allGold",!units.isEmpty()&&covered==units.size());
    }
    private Map<String,Object> summary(List<Map<String,Object>> cases,String field){
        var answerable=cases.stream().filter(c->Boolean.TRUE.equals(c.get("answerable"))).toList();double recall=0,mrr=0;int all=0;
        for(var row:answerable){var score=(Map<?,?>)row.get(field);recall+=((Number)score.get("recallAt5")).doubleValue();mrr+=((Number)score.get("mrrAt5")).doubleValue();if(Boolean.TRUE.equals(score.get("allGold")))all++;}
        return Map.of("denominator",answerable.size(),"macroRecallAt5",recall/answerable.size(),"mrrAt5",mrr/answerable.size(),"allGold",all);
    }
    @Test void replayFrozenDevWithHistoricalVectorsAndCurrentLuceneQueryPolicy()throws Exception{
        String packageRoot=System.getenv("RETRIEVAL_REPLAY_PACKAGE"),resultsFile=System.getenv("RETRIEVAL_REPLAY_RESULTS"),output=System.getenv("RETRIEVAL_REPLAY_OUTPUT");
        org.junit.jupiter.api.Assumptions.assumeTrue(packageRoot!=null&&resultsFile!=null&&output!=null,"explicit private DEV replay paths required");
        Path pack=Path.of(packageRoot);var binding=read(pack.resolve("runtime-binding.json"));var sourceMap=binding.path("sources_by_point_id");
        var chunks=new ArrayList<Chunk>();for(var chunk:lines(pack.resolve("corpus.jsonl"))){
            String id=binding.path("old_to_new_chunk_ids").path(chunk.path("chunkId").asText()).asText();assertFalse(id.isBlank());
            chunks.add(new Chunk(id,chunk.path("chunkIndex").asInt(),chunk.path("content").asText(),chunk.path("pageStart").asInt(),null));
        }
        var queries=lines(pack.resolve("queries.jsonl")).stream().filter(q->"DEV".equals(q.path("group").asText())).toList();assertEquals(20,queries.size());
        var gold=new HashMap<String,JsonNode>();for(var row:read(pack.resolve("runtime-gold.json")))if("DEV".equals(row.path("group").asText()))gold.put(row.path("id").asText(),row);
        var old=new HashMap<String,JsonNode>();for(var row:read(Path.of(resultsFile)))if("DEV".equals(row.path("group").asText())&&"hybrid-rrf-v1".equals(row.path("configuration").asText()))old.put(row.path("id").asText(),row);
        var cases=new ArrayList<Map<String,Object>>();
        try(var index=new LuceneKnowledgeIndex(localIndex)){
            var generation=new Generation(binding.path("generation").asText(),1,1,"layout","config",KnowledgeIndexPipeline.manifest(chunks),"offline-replay",Map.of("modelScope","MODELS","models",List.of("HL-L2350DW")),chunks);
            index.write(generation);
            for(var query:queries){
                String id=query.path("id").asText(),model=query.path("canonical_model").asText();var prior=old.get(id);assertNotNull(prior);
                var vector=nodes(prior.path("retrieval").path("trace").path("ranking")).stream().filter(r->!r.path("vectorRank").isNull())
                    .sorted(Comparator.comparingInt(r->r.path("vectorRank").asInt())).map(r->new IndexQuery.Hit(generation.id(),r.path("chunkId").asText(),r.path("vectorScore").asDouble())).toList();
                var lexical=index.search(new IndexQuery.Scope(model,Set.of(generation.id())),query.path("retrieval_query").asText());
                var fused=HybridRanking.fuse(vector,lexical);var admitted=fused.stream().filter(r->r.bm25Rank()!=null||(r.vectorScore()!=null&&r.vectorScore()>=0.5)).limit(20).toList();
                var units=admitted.stream().map(r->{var s=sourceMap.path(binding.path("chunk_to_point").path(r.chunkId()).asText());assertFalse(s.isMissingNode());
                    return new HybridRanking.Unit(r.chunkId(),s.path("title").asText(),s.path("section").asText(),s.path("content").asText(),json.writeValueAsString(s));}).toList();
                var context=HybridRanking.context(units,5744);var selected=new ArrayList<String>();int display=200;
                for(var unit:context.units()){var s=sourceMap.path(binding.path("chunk_to_point").path(unit.chunkId()).asText());
                    int size=unit.text().codePointCount(0,unit.text().length())+unit.title().length()+s.path("useCondition").asText().length()+String.join("、",strings(s.path("models"))).length()+200;
                    if(display+size<=4000){selected.add(unit.chunkId());display+=size;}
                }
                var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("answerable",gold.get(id).path("binding").path("semantic_units").size()>0);
                result.put("historicalReturned",score(gold.get(id),strings(prior.path("returned_chunk_ids"))));result.put("replayReturned",score(gold.get(id),selected));
                result.put("beforeContextTop5",score(gold.get(id),admitted.stream().map(HybridRanking.Ranked::chunkId).toList()));
                result.put("lexicalCandidateCount",lexical.size());result.put("newRanking",fused);result.put("returnedChunkIds",selected);
                result.put("contextByteUpperBound",context.tokenUpperBound());result.put("displayDropped",context.units().size()-selected.size());cases.add(result);
            }
        }
        var report=new LinkedHashMap<String,Object>();report.put("kind","OFFLINE_DEV_REPLAY_HISTORICAL_VECTORS_NOT_NEW_MODEL_QUALITY");report.put("externalCalls",0);
        report.put("queryPolicy",LuceneKnowledgeIndex.QUERY_POLICY);report.put("historicalRrf",summary(cases,"historicalReturned"));report.put("replayRrf",summary(cases,"replayReturned"));report.put("cases",cases);
        Path out=Path.of(output);Files.createDirectories(out.toAbsolutePath().getParent());Files.writeString(out,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("DEV_REPLAY "+json.writeValueAsString(Map.of("historical",summary(cases,"historicalReturned"),"replay",summary(cases,"replayReturned"))));
    }
}

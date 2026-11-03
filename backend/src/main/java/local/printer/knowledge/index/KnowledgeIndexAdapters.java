package local.printer.knowledge.index;

import java.util.List;
import java.util.Map;

/** Ports for derived indexes. No implementation is selected implicitly. */
public final class KnowledgeIndexAdapters {
    private KnowledgeIndexAdapters() {}
    public record Chunk(String id,int index,String text,Integer page,String section) {}
    public record Generation(String id,long documentId,int revision,String layoutHash,String configurationHash,
                             String manifestHash,String documentKey,Map<String,Object> metadata,List<Chunk> chunks) {}
    public interface Embedding {
        /** Matches the separately approved processingPermissions.embedding.configurationId. */
        String configurationId();
        /** Immutable endpoint/model/dimensions implementation identity, without secrets. */
        String identity();
        int dimensions();
        List<List<Double>> embed(List<String> texts);
    }
    public interface Vector {
        String identity();
        /** Idempotently upsert deterministic generation/chunk IDs using saved embedding results. */
        void write(Generation generation,List<Chunk> chunks,List<List<Double>> embeddings);
        Receipt verify(Generation generation);
    }
    public interface Bm25 {
        String identity();
        void write(Generation generation);
        Receipt verify(Generation generation);
    }
    /** Exact ID set and configuration binding are mandatory, a count-only receipt is insufficient. */
    public record Receipt(String generationId,String configurationHash,String manifestHash,List<String> chunkIds) {}
}

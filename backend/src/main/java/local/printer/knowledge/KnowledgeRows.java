package local.printer.knowledge;

import java.time.LocalDate;

/** Internal metadata and persistence fixtures; never HTTP DTOs. */
public final class KnowledgeRows {
    private KnowledgeRows() {}
    public record Document(Long id, String documentKey, String version, String title, String sourceType,
            boolean selfAuthored, boolean simulated, String modelScope, String modelsJson,
            String sourceUrl, String officialIndex, String termsUrl, String brand, String region,
            String language, LocalDate acquiredOn, LocalDate checkedOn, String useCondition,
            boolean distributionAllowed, boolean externalUploadAllowed, String localFile,
            String provenanceJson, String contentHash, String status, boolean active) {
        @Override public String toString() { return "Document[REDACTED]"; }
    }
    public record Chunk(Long id, long documentId, int chunkIndex, String content, Integer pageNumber, String section) {
        @Override public String toString() { return "Chunk[REDACTED]"; }
    }
    public static final class NewRow<T> {
        private Long id;
        private final T row;
        public NewRow(T row) { this.row = row; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public T getRow() { return row; }
        @Override public String toString() { return "NewRow[REDACTED]"; }
    }
}

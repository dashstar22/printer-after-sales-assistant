package local.printer.knowledge;

/** Fixed public codes only; never attach raw input or a database exception. */
public final class KnowledgeFailure extends RuntimeException {
    private final int exitCode;
    public KnowledgeFailure(String code, int exitCode) { super(code); this.exitCode = exitCode; }
    public int exitCode() { return exitCode; }
    public static KnowledgeFailure invalid(String code) { return new KnowledgeFailure(code, 2); }
}

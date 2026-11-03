package local.printer.administration;

public final class AdminFailure extends RuntimeException {
    public final int status;
    public final String code;
    public AdminFailure(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public static AdminFailure invalid(String message) { return new AdminFailure(400,"INVALID_ARGUMENT",message); }
    public static AdminFailure conflict(String code) { return new AdminFailure(409,code,"记录已变化或输入冲突，请刷新核对"); }
}

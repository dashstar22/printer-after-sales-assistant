package local.printer.auth.email;

public final class EmailFailure extends RuntimeException {
    public final int status;
    public final String code;
    public EmailFailure(int status,String code,String message){super(message);this.status=status;this.code=code;}
    public static EmailFailure invalid(){return new EmailFailure(400,"EMAIL_ARGUMENT_INVALID","邮箱信息或请求字段不正确");}
    public static EmailFailure token(){return new EmailFailure(400,"EMAIL_LINK_INVALID","链接无效或已过期，请重新申请");}
    public static EmailFailure conflict(){return new EmailFailure(409,"EMAIL_CONFLICT","邮箱已被账号使用，请恢复原账号或联系人工");}
}

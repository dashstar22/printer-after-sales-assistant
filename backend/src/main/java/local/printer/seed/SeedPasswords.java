package local.printer.seed;

/** 不参与配置属性绑定，避免配置校验将密码回显到日志。 */
public record SeedPasswords(String customerA, String customerB, String staff) {
    @Override public String toString() { return "SeedPasswords[REDACTED]"; }
}

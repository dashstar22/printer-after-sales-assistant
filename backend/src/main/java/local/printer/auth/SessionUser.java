package local.printer.auth;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** 仅保存在服务端 SecurityContext；对外始终使用不含密码的 Identity DTO。 */
public final class SessionUser extends User {
    private final long id;
    private final String role;
    private final long credentialVersion;
    private final boolean mustChangePassword;
    private final boolean passwordAuthenticated;

    public SessionUser(AuthUserMapper.Account account) {
        this(account,false);
    }
    public SessionUser(AuthUserMapper.Account account,boolean passwordAuthenticated) {
        super(account.username(), account.passwordHash(), "ACTIVE".equals(account.status()), true, true, true,
            List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
        this.id = account.id();
        this.role = account.role();
        this.credentialVersion=account.credentialVersion();
        this.mustChangePassword=account.mustChangePassword();
        this.passwordAuthenticated=passwordAuthenticated;
    }

    public Identity identity() { return new Identity(id, getUsername(), role); }
    public record Identity(long id, String username, String role) {}
    public long credentialVersion(){return credentialVersion;}
    public boolean mustChangePassword(){return mustChangePassword;}
    public boolean passwordAuthenticated(){return passwordAuthenticated;}
}

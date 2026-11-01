package local.printer.auth;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** 仅保存在服务端 SecurityContext；对外始终使用不含密码的 Identity DTO。 */
public final class SessionUser extends User {
    private final long id;
    private final String role;

    public SessionUser(AuthUserMapper.Account account) {
        super(account.username(), account.passwordHash(), List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
        this.id = account.id();
        this.role = account.role();
    }

    public Identity identity() { return new Identity(id, getUsername(), role); }
    public record Identity(long id, String username, String role) {}
}

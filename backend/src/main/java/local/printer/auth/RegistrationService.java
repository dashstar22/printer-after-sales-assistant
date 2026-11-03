package local.printer.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RegistrationService {
    private final AuthUserMapper users;
    private final PasswordEncoder passwords;
    public RegistrationService(AuthUserMapper users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }
    public record Registered(boolean registered, String username) {}
    public static final class Invalid extends RuntimeException {
        public Invalid(String message) { super(message); }
    }
    public static final class AccountExists extends RuntimeException {}

    /** Retired username/password registration contract; no unverified identity creation. */
    public Registered register(JsonNode body) {
        throw new Invalid("请先申请邮箱验证链接，再设置密码完成注册");
    }
}

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

    public Registered register(JsonNode body) {
        if (body == null || !body.isObject() || !body.propertyNames().equals(Set.of("username", "password", "passwordConfirmation")))
            throw new Invalid("请填写账号、密码和确认密码");
        for (String key : body.propertyNames()) if (!body.get(key).isString()) throw new Invalid("注册信息格式不正确");
        String username = body.get("username").asString().strip().toLowerCase(Locale.ROOT);
        String password = body.get("password").asString();
        if (!username.matches("[a-z0-9_]{4,32}")) throw new Invalid("账号须为 4–32 位字母、数字或下划线");
        int length = password.codePointCount(0, password.length());
        if (length < 8 || length > 64 || password.isBlank() || password.codePoints().anyMatch(Character::isISOControl))
            throw new Invalid("密码须为 8–64 位有效字符");
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw new Invalid("密码过长，请缩短后重试");
        if (!password.equals(body.get("passwordConfirmation").asString())) throw new Invalid("两次输入的密码不一致");
        // The unique index resolves races. The SQL fixes the role, never accepting client authority.
        try { users.registerCustomer(username, passwords.encode(password)); }
        catch (DuplicateKeyException duplicate) { throw new AccountExists(); }
        return new Registered(true, username);
    }
}

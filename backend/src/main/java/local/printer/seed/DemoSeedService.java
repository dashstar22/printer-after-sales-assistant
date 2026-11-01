package local.printer.seed;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 本地演示数据导入，无 HTTP 入口，不在启动事件上执行。 */
@Service
public class DemoSeedService {
    public static final String MODEL = "HL-L2350DW";
    public static final List<String> USERS = List.of("demo_customer_a", "demo_customer_b", "demo_staff");
    private final SeedMapper mapper;
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    public DemoSeedService(SeedMapper mapper) { this.mapper = mapper; }

    public record Result(int usersCreated, int modelsCreated, int ordersCreated) {}
    public static class SeedFailure extends RuntimeException {
        public SeedFailure(String message) { super(message); }
    }

    @Transactional
    public Result seed(SeedPasswords passwords) {
        var supplied = new String[] {passwords.customerA(), passwords.customerB(), passwords.staff()};
        var keys = new String[] {"SEED_CUSTOMER_A_PASSWORD", "SEED_CUSTOMER_B_PASSWORD", "SEED_STAFF_PASSWORD"};
        long[] ids = new long[3];
        int usersCreated = 0;
        for (int i = 0; i < USERS.size(); i++) {
            String name = USERS.get(i);
            String role = i == 2 ? "STAFF" : "CUSTOMER";
            var existing = mapper.user(name);
            if (existing == null) {
                String raw = supplied[i];
                if (raw == null || raw.isBlank() || raw.codePointCount(0, raw.length()) < 12
                        || raw.getBytes(StandardCharsets.UTF_8).length > 72) {
                    throw new SeedFailure("SEED_PASSWORD_REQUIRED: " + keys[i] + " 需配置至少12字符、至多72个UTF-8字节的初始密码");
                }
                mapper.insertUser(name, encoder.encode(raw), role);
                usersCreated++;
                existing = mapper.user(name);
            }
            if (!name.equals(existing.username()) || !role.equals(existing.role())
                    || !existing.passwordHash().matches("\\{bcrypt}\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}")) {
                throw new SeedFailure("SEED_CONFLICT: 预置账号身份或密码存储格式不一致；未覆盖已有账号");
            }
            ids[i] = existing.id();
        }
        int modelsCreated = 0;
        var model = mapper.model(MODEL);
        if (model == null) {
            mapper.insertModel(MODEL);
            modelsCreated++;
            model = mapper.model(MODEL);
        }
        if (!MODEL.equals(model.modelCode()) || !"Brother".equals(model.brand()) || !model.demo()) {
            throw new SeedFailure("SEED_CONFLICT: 演示型号品牌或模拟标记不一致；未覆盖已有型号");
        }
        int ordersCreated = 0;
        for (int i = 0; i < 2; i++) {
            String number = "DEMO-ORDER-00" + (i + 1);
            String serial = "DEMO-NOT-REAL-HLL2350DW-00" + (i + 1);
            LocalDate date = LocalDate.of(2026, 9, i + 1);
            var existing = mapper.order(number);
            if (existing == null) {
                mapper.insertOrder(number, ids[i], model.id(), serial, date);
                ordersCreated++;
            } else if (!number.equals(existing.orderNo()) || existing.customerId() != ids[i]
                    || existing.modelId() != model.id() || !serial.equals(existing.serialNo())
                    || !date.equals(existing.purchaseDate()) || !existing.demo()) {
                throw new SeedFailure("SEED_CONFLICT: 模拟订单归属、型号或内容不一致；未覆盖已有订单");
            }
        }
        return new Result(usersCreated, modelsCreated, ordersCreated);
    }
}

package local.printer.seed;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DemoSeedIT {
    @Autowired DemoSeedService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    private SeedPasswords passwords;
    private TransactionTemplate tx;

    @BeforeEach void isolatedRealMySqlOnly() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("printer_seed_it_"),
                "测试必须指向独立 printer_seed_it_ 库，禁止在业务库运行");
        assertFalse(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("root@"));
        assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8.4."));
        passwords = new SeedPasswords(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        tx = new TransactionTemplate(manager);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void rollback(Runnable scenario) {
        var before = List.of(count("users"), count("device_models"), count("orders"));
        tx.executeWithoutResult(status -> {
            try { scenario.run(); } finally { status.setRollbackOnly(); }
        });
        assertEquals(before, List.of(count("users"), count("device_models"), count("orders")));
    }

    @Test void initialImportOwnershipAndPasswordHashes() {
        rollback(() -> {
            assertEquals(new DemoSeedService.Result(3, 1, 2), service.seed(passwords));
            var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
            var raw = new String[] {passwords.customerA(), passwords.customerB(), passwords.staff()};
            for (int i = 0; i < 3; i++) {
                var row = jdbc.queryForMap("SELECT role,password_hash FROM users WHERE username=?", DemoSeedService.USERS.get(i));
                String hash = (String) row.get("password_hash");
                assertTrue(hash.startsWith("{bcrypt}"));
                assertTrue(!raw[i].equals(hash));
                assertTrue(encoder.matches(raw[i], hash));
                assertFalse(encoder.matches("incorrect-password-for-verification", hash));
                assertEquals(i == 2 ? "STAFF" : "CUSTOMER", row.get("role"));
            }
            var rows = jdbc.queryForList("SELECT u.username,m.model_code,m.brand,m.is_demo AS model_demo,o.is_demo AS order_demo,o.serial_no FROM orders o JOIN users u ON o.customer_id=u.id JOIN device_models m ON o.model_id=m.id ORDER BY o.order_no");
            assertEquals(2, rows.size());
            for (int i = 0; i < 2; i++) {
                var row = rows.get(i);
                assertEquals(DemoSeedService.USERS.get(i), row.get("username"));
                assertEquals("HL-L2350DW", row.get("model_code"));
                assertEquals("Brother", row.get("brand"));
                assertEquals(Boolean.TRUE, row.get("model_demo"));
                assertEquals(Boolean.TRUE, row.get("order_demo"));
                assertTrue(row.get("serial_no").toString().startsWith("DEMO-NOT-REAL-"));
            }
        });
    }

    @Test void repeatDoesNotChangeIdsHashesOrOrdersEvenWithDifferentPasswords() {
        rollback(() -> {
            service.seed(passwords);
            var users = jdbc.queryForList("SELECT * FROM users ORDER BY id");
            var orders = jdbc.queryForList("SELECT * FROM orders ORDER BY id");
            var models = jdbc.queryForList("SELECT * FROM device_models ORDER BY id");
            assertEquals(new DemoSeedService.Result(0, 0, 0), service.seed(new SeedPasswords(null, null, null)));
            assertEquals(new DemoSeedService.Result(0, 0, 0), service.seed(new SeedPasswords("changed-value-a", "changed-value-b", "changed-value-c")));
            // 布尔断言避免失败时将密码哈希回显。
            assertTrue(users.equals(jdbc.queryForList("SELECT * FROM users ORDER BY id")));
            assertEquals(orders, jdbc.queryForList("SELECT * FROM orders ORDER BY id"));
            assertEquals(models, jdbc.queryForList("SELECT * FROM device_models ORDER BY id"));
        });
    }

    @Test void missingPasswordRollsBackEarlierInserts() {
        assertThrows(DemoSeedService.SeedFailure.class, () -> service.seed(new SeedPasswords(passwords.customerA(), null, passwords.staff())));
        assertEquals(0, count("users"));
        assertEquals(0, count("device_models"));
        assertEquals(0, count("orders"));
    }

    @Test void rejectsBlankShortAndOver72Utf8Bytes() {
        for (String invalid : List.of("   ", "short", "汉".repeat(25))) {
            assertThrows(DemoSeedService.SeedFailure.class, () -> service.seed(new SeedPasswords(invalid, null, null)));
            assertEquals(0, count("users"));
        }
    }

    @Test void roleConflictPreservesFixture() {
        rollback(() -> {
            jdbc.update("INSERT INTO users(username,password_hash,role) VALUES('demo_customer_a',?,'STAFF')",
                    PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(passwords.staff()));
            assertThrows(DemoSeedService.SeedFailure.class, () -> service.seed(passwords));
            assertEquals("STAFF", jdbc.queryForObject("SELECT role FROM users WHERE username='demo_customer_a'", String.class));
        });
    }

    @Test void modelConflictAfterUserInsertsRollsBack() {
        assertThrows(DemoSeedService.SeedFailure.class, () -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO device_models(model_code,brand,is_demo) VALUES('HL-L2350DW','CONFLICT-FIXTURE',true)");
            service.seed(passwords);
        }));
        assertEquals(0, count("users"));
        assertEquals(0, count("device_models"));
    }

    @Test void orderOwnershipConflictIsNotOverwritten() {
        rollback(() -> {
            service.seed(passwords);
            jdbc.update("UPDATE orders SET customer_id=(SELECT id FROM users WHERE username='demo_customer_b') WHERE order_no='DEMO-ORDER-001'");
            var before = jdbc.queryForList("SELECT * FROM orders ORDER BY id");
            assertThrows(DemoSeedService.SeedFailure.class, () -> service.seed(passwords));
            assertEquals(before, jdbc.queryForList("SELECT * FROM orders ORDER BY id"));
        });
    }

    @Test void serialDateModelAndDemoFlagConflictsAreRejected() {
        for (String assignment : List.of("serial_no='CONFLICT-FIXTURE'", "purchase_date='2000-01-01'", "is_demo=false")) {
            rollback(() -> {
                service.seed(passwords);
                jdbc.update("UPDATE orders SET " + assignment + " WHERE order_no='DEMO-ORDER-001'");
                var before = jdbc.queryForList("SELECT * FROM orders ORDER BY id");
                assertThrows(DemoSeedService.SeedFailure.class, () -> service.seed(passwords));
                assertEquals(before, jdbc.queryForList("SELECT * FROM orders ORDER BY id"));
            });
        }
        rollback(() -> {
            service.seed(passwords);
            jdbc.update("INSERT INTO device_models(model_code,brand,is_demo) VALUES('TEST-ONLY-OTHER-MODEL','Fixture',true)");
            jdbc.update("UPDATE orders SET model_id=(SELECT id FROM device_models WHERE model_code='TEST-ONLY-OTHER-MODEL') WHERE order_no='DEMO-ORDER-001'");
            var before = jdbc.queryForList("SELECT * FROM orders ORDER BY id");
            assertThrows(DemoSeedService.SeedFailure.class, () -> service.seed(passwords));
            assertEquals(before, jdbc.queryForList("SELECT * FROM orders ORDER BY id"));
        });
    }

    @Test void existingPlaintextPasswordIsNotAdoptedOrOverwritten() {
        rollback(() -> {
            jdbc.update("INSERT INTO users(username,password_hash,role) VALUES('demo_customer_a','TEST-ONLY-INVALID-HASH','CUSTOMER')");
            assertThrows(DemoSeedService.SeedFailure.class, () -> service.seed(passwords));
            assertTrue("TEST-ONLY-INVALID-HASH".equals(jdbc.queryForObject("SELECT password_hash FROM users WHERE username='demo_customer_a'", String.class)));
        });
    }
}

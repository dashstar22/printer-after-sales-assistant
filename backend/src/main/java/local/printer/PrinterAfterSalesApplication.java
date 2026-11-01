package local.printer;

import local.printer.config.DependencyProperties;
import local.printer.knowledge.KnowledgeCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import java.util.Arrays;
import local.printer.seed.DemoSeedService;
import local.printer.seed.SeedPasswords;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DependencyProperties.class)
public class PrinterAfterSalesApplication {
    public static void main(String[] args) {
        if (KnowledgeCommand.requested(args)) {
            int code = KnowledgeCommand.run(PrinterAfterSalesApplication.class, args);
            if (code != 0) System.exit(code);
            return;
        }
        if (!Arrays.asList(args).contains("--seed-demo")) {
            SpringApplication.run(PrinterAfterSalesApplication.class, args);
            return;
        }
        var application = new SpringApplication(PrinterAfterSalesApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        int exitCode = 0;
        try (var context = application.run(args)) {
            try {
                var env = context.getEnvironment();
                var passwords = new SeedPasswords(env.getProperty("SEED_CUSTOMER_A_PASSWORD"),
                        env.getProperty("SEED_CUSTOMER_B_PASSWORD"), env.getProperty("SEED_STAFF_PASSWORD"));
                var result = context.getBean(DemoSeedService.class).seed(passwords);
                System.out.println("SEED_OK " + result);
            } catch (RuntimeException failure) {
                // 不输出 MyBatis 异常链、参数、密码或哈希。
                System.err.println(failure instanceof DemoSeedService.SeedFailure
                        ? failure.getMessage() : "SEED_DATABASE_ERROR: 导入失败，事务已回滚；核对数据库状态与连接配置");
                exitCode = 2;
            }
        }
        if (exitCode != 0) System.exit(exitCode);
    }
}

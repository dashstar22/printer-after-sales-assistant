package local.printer;

import javax.sql.DataSource;
import java.util.List;
import local.printer.config.DependencyProperties;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

/** 显式 -Pmysql-it 才运行，要求真实 MySQL；不替换 DataSource。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InfrastructureIT {
    @Autowired SqlSessionFactory sessions;
    @Autowired DataSource dataSource;
    @Autowired DependencyProperties dependencies;

    interface ProbeMapper {
        @Select("SELECT VERSION()") String version();
        @Select("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank") List<String> migrations();
        @Select("SELECT CURRENT_USER()") String currentUser();
    }

    @Test void realMySqlFlywayAndMyBatis() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
        }
        if (!sessions.getConfiguration().hasMapper(ProbeMapper.class)) {
            sessions.getConfiguration().addMapper(ProbeMapper.class);
        }
        try (var session = sessions.openSession()) {
            var mapper = session.getMapper(ProbeMapper.class);
            assertTrue(mapper.version().startsWith("8.4."));
            assertFalse(mapper.currentUser().startsWith("root@"));
            assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"), mapper.migrations());
        }
        assertFalse(dependencies.ai().enabled());
        assertEquals("", dependencies.ai().apiKey());
        assertEquals("", dependencies.ai().chatModel());
    }
}

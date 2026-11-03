package local.printer.operations;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** /api/admin/** is authenticated and ADMIN-only in the existing security chain. */
@RestController
public class OperationsController {
    private final ExternalCallBudget budget;private final RedisProtection redis;private final JdbcTemplate db;private final Environment env;
    public OperationsController(ExternalCallBudget budget,RedisProtection redis,JdbcTemplate db,Environment env){this.budget=budget;this.redis=redis;this.db=db;this.env=env;}
    @GetMapping("/api/admin/operations")
    public ResponseEntity<Map<String,Object>> status(){
        var result=new LinkedHashMap<String,Object>();result.put("budget",budget.status());result.put("redis",redis.state());
        result.put("mail",db.queryForList("SELECT status,COUNT(*) AS count FROM email_sends GROUP BY status"));
        result.put("jobs",db.queryForList("SELECT state,COUNT(*) AS count FROM knowledge_jobs GROUP BY state"));
        result.put("automationEnabled",env.getProperty("app.automation.enabled",Boolean.class,false));
        result.put("notification",db.queryForList("SELECT status,COUNT(*) AS count FROM notification_deliveries GROUP BY status"));
        // Historical acceptance belongs to its evidence package, not to a hard-coded
        // live health flag. Report only the mode configured for this instance.
        result.put("deploymentGate","PRODUCTION_READINESS_NOT_ASSERTED");
        result.put("deploymentMode",env.getProperty("PORTFOLIO_MODE", "standard"));
        result.put("probeMeaning","Observed records/configuration; health alone does not prove business success");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}

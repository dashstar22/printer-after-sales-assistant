package local.printer.jobs;

import local.printer.auth.SessionUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/admin/knowledge/jobs")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeJobController {
    private final KnowledgeJobStore store;
    public KnowledgeJobController(KnowledgeJobStore store){this.store=store;}
    @GetMapping("/{id}")
    JsonNode detail(@AuthenticationPrincipal SessionUser actor,@PathVariable String id){return store.detail(actor,id);}
    @PostMapping("/{id}/retry")
    ResponseEntity<JsonNode> retry(@AuthenticationPrincipal SessionUser actor,@PathVariable String id,@RequestBody JsonNode body){return ResponseEntity.accepted().body(store.retry(actor,id,body));}
}

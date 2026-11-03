package local.printer.administration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import local.printer.auth.SessionUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/admin")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class AdministrationController {
    private final AdminLedger ledger;
    private final AccountAdministration accounts;
    private final CatalogAdministration catalog;
    private final OrderImportService imports;
    public AdministrationController(AdminLedger ledger,AccountAdministration accounts,CatalogAdministration catalog,OrderImportService imports){this.ledger=ledger;this.accounts=accounts;this.catalog=catalog;this.imports=imports;}
    @GetMapping("/accounts") JsonNode accounts(@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="20")int pageSize){return ledger.page(AccountAdministration.SELECT,cursor,pageSize);}
    @PostMapping("/accounts") JsonNode createAccount(@AuthenticationPrincipal SessionUser actor,@RequestBody JsonNode body){return accounts.create(actor,body);}
    @PatchMapping("/accounts/{id}") JsonNode accountStatus(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody JsonNode body){return accounts.status(actor,id,body);}
    @PostMapping("/accounts/{id}/password-reset") JsonNode reset(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody JsonNode body){return accounts.reset(actor,id,body);}
    @GetMapping("/models") JsonNode models(@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="20")int pageSize){return ledger.page(CatalogAdministration.MODELS,cursor,pageSize);}
    @GetMapping("/models/{id}") JsonNode model(@PathVariable long id){return catalog.modelDetail(id);}
    @GetMapping("/model-resolution") Map<String,Object> resolve(@RequestParam String input){return Map.of("input",input,"modelId",catalog.resolve(input));}
    @PostMapping("/models") JsonNode createModel(@AuthenticationPrincipal SessionUser actor,@RequestBody JsonNode body){return catalog.model(actor,null,body);}
    @PatchMapping("/models/{id}") JsonNode updateModel(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody JsonNode body){return catalog.model(actor,id,body);}
    @GetMapping("/orders") JsonNode orders(@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="20")int pageSize){return ledger.page(CatalogAdministration.ORDERS,cursor,pageSize);}
    @GetMapping("/orders/{id}") JsonNode order(@PathVariable long id){return catalog.orderDetail(id);}
    @PostMapping("/orders") JsonNode createOrder(@AuthenticationPrincipal SessionUser actor,@RequestBody JsonNode body){return catalog.order(actor,null,body);}
    @PatchMapping("/orders/{id}") JsonNode updateOrder(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody JsonNode body){return catalog.order(actor,id,body);}
    @PostMapping("/orders/{id}/void") JsonNode voidOrder(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody JsonNode body){return catalog.voidOrder(actor,id,body);}
    @GetMapping(value="/order-imports/template",produces="text/csv;charset=UTF-8") ResponseEntity<byte[]> template(){return ResponseEntity.ok().header("Content-Disposition","attachment; filename=orders.csv").body(("\uFEFF"+OrderImportService.HEADER+"\r\n").getBytes(StandardCharsets.UTF_8));}
    @PostMapping(value="/order-imports",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    JsonNode preview(@AuthenticationPrincipal SessionUser actor,@RequestParam String requestId,@RequestPart("file") MultipartFile file)throws IOException {
        if(file.getSize()>1024*1024)throw new AdminFailure(413,"RESOURCE_LIMIT","CSV不能超过1MB");
        return imports.preview(actor,requestId,file.getBytes());
    }
    @GetMapping("/order-imports/{id}") JsonNode importStatus(@AuthenticationPrincipal SessionUser actor,@PathVariable String id){return imports.detail(actor,id);}
    @PostMapping("/order-imports/{id}/commit") JsonNode commit(@AuthenticationPrincipal SessionUser actor,@PathVariable String id,@RequestBody JsonNode body){return imports.commit(actor,id,body);}
    @GetMapping("/audit") JsonNode audit(@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="20")int pageSize){return ledger.page("SELECT id,actor_id AS actorId,action,object_id AS objectId,result,request_id AS requestId,created_at AS createdAt FROM administration_audit",cursor,pageSize);}
}

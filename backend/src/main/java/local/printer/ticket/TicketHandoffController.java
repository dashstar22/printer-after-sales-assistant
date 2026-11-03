package local.printer.ticket;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import local.printer.auth.SessionUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class TicketHandoffController {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final TicketHandoffService service;
    private final TicketAttachmentService attachments;
    public TicketHandoffController(TicketHandoffService service,TicketAttachmentService attachments){this.service=service;this.attachments=attachments;}
    private JsonNode input(String raw,String... fields){
        try{var body=JSON.readTree(raw);if(body==null||!body.isObject()||!body.propertyNames().equals(Set.of(fields)))throw TicketHandoffService.invalid();return body;}
        catch(tools.jackson.core.JacksonException invalid){throw TicketHandoffService.invalid();}
    }
    private long number(JsonNode body,String key){var n=body.get(key);if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.asLong()<0)throw TicketHandoffService.invalid();return n.asLong();}
    private String text(JsonNode body,String key){var n=body.get(key);if(n==null||!n.isString())throw TicketHandoffService.invalid();return n.asString();}
    private ResponseEntity<JsonNode> ok(JsonNode body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
    @PostMapping(value="/api/staff/tickets/{id}/transfer",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> transfer(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody String raw){
        var body=input(raw,"targetStaffId","reason","expectedVersion","requestId");
        return ok(service.transfer(actor.identity(),id,number(body,"expectedVersion"),number(body,"targetStaffId"),text(body,"reason"),text(body,"requestId")));
    }
    @PostMapping(value="/api/tickets/{id}/supplements",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> supplement(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody String raw){
        var body=input(raw,"text","expectedVersion","requestId");
        return ok(service.supplement(actor.identity(),id,number(body,"expectedVersion"),text(body,"text"),text(body,"requestId")));
    }
    @GetMapping("/api/tickets/{id}/conversation-context")
    ResponseEntity<JsonNode> context(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="20")int pageSize){return ok(service.context(actor.identity(),id,cursor,pageSize));}
    @PostMapping(value="/api/tickets/{id}/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<JsonNode> upload(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestParam long expectedVersion,@RequestParam String requestId,
        @RequestPart("files") List<MultipartFile> files,HttpServletRequest request)throws java.io.IOException,jakarta.servlet.ServletException {
        var counts=new HashMap<String,Integer>();for(var part:request.getParts())counts.merge(part.getName(),1,Integer::sum);
        if(!counts.keySet().equals(Set.of("files","expectedVersion","requestId"))||counts.get("expectedVersion")!=1||counts.get("requestId")!=1)throw TicketHandoffService.invalid();
        return ok(attachments.upload(actor.identity(),id,expectedVersion,requestId,files));
    }
    @GetMapping("/api/tickets/{id}/attachments")
    ResponseEntity<JsonNode> list(@AuthenticationPrincipal SessionUser actor,@PathVariable long id){return ok(attachments.list(actor.identity(),id));}
    @GetMapping("/api/tickets/{id}/attachments/{attachment}")
    ResponseEntity<byte[]> read(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@PathVariable long attachment){
        var file=attachments.read(actor.identity(),id,attachment);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.mime()))
            .header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline; filename=photo"+(file.mime().equals("image/png")?".png":".jpg")).body(file.bytes());
    }
}

package local.printer.knowledge;

import java.io.IOException;
import local.printer.auth.SessionUser;
import local.printer.administration.AdminFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.*;

@RestController
@RequestMapping("/api/admin/knowledge")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeManagementController {
    private final KnowledgeManagementService application;private final ObjectMapper json;
    public KnowledgeManagementController(KnowledgeManagementService application,ObjectMapper json){this.application=application;this.json=json;}
    @GetMapping JsonNode list(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0")long cursor,
            @RequestParam(defaultValue="20")int pageSize){return application.list(actor,cursor,pageSize);}
    @GetMapping("/options") JsonNode options(@AuthenticationPrincipal SessionUser actor){return application.options(actor);}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    JsonNode upload(@AuthenticationPrincipal SessionUser actor,@RequestPart("metadata") String metadata,@RequestPart("file") MultipartFile file)throws IOException {
        return application.upload(actor,null,metadata(metadata),file.getOriginalFilename(),bytes(file));
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    JsonNode faq(@AuthenticationPrincipal SessionUser actor,@RequestBody JsonNode body){return application.faq(actor,null,body);}
    @PostMapping(value="/{id}/revisions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    JsonNode revision(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestPart("metadata") String metadata,@RequestPart("file") MultipartFile file)throws IOException {
        return application.upload(actor,id,metadata(metadata),file.getOriginalFilename(),bytes(file));
    }
    @PostMapping(value="/{id}/revisions",consumes=MediaType.APPLICATION_JSON_VALUE)
    JsonNode faqRevision(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody JsonNode body){return application.faq(actor,id,body);}
    @GetMapping("/{id}") JsonNode detail(@PathVariable long id){return application.detail(id);}
    @GetMapping("/{id}/preview")
    JsonNode preview(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestParam int revision,
            @RequestParam(defaultValue="0")int cursor,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String previewToken) {
        return application.preview(actor,id,revision,cursor,pageSize,previewToken);
    }
    @PostMapping("/{id}/publish") ResponseEntity<JsonNode> publish(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody JsonNode body) {
        return ResponseEntity.accepted().body(application.publish(actor,id,body));
    }
    @PostMapping("/{id}/disable") JsonNode disable(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody JsonNode body) {
        return application.disable(actor,id,body);
    }
    @GetMapping("/{id}/original") ResponseEntity<byte[]> original(@PathVariable long id,@RequestParam int revision) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header("Content-Disposition","attachment; filename=knowledge-original.bin")
            .header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store").body(application.original(id,revision));
    }
    private JsonNode metadata(String input) {
        if(input.length()>65536)throw new AdminFailure(413,"RESOURCE_LIMIT","元数据过长");
        try {return json.readTree(input);}catch(RuntimeException e){throw AdminFailure.invalid("元数据须为JSON");}
    }
    private byte[] bytes(MultipartFile file)throws IOException {
        if(file.getSize()>KnowledgeTextExtractor.MAX_BYTES)throw new AdminFailure(413,"RESOURCE_LIMIT","文件不能超过20MB");
        try(var input=file.getInputStream()) {
            byte[] bytes=input.readNBytes(KnowledgeTextExtractor.MAX_BYTES+1);
            if(bytes.length>KnowledgeTextExtractor.MAX_BYTES)throw new AdminFailure(413,"RESOURCE_LIMIT","文件不能超过20MB");return bytes;
        }
    }
}

package local.printer.integration;

import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.*;
import local.printer.auth.SessionUser;
import local.printer.common.ApiResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class AutomationController {
    private final NotificationDeliveries service;
    AutomationController(NotificationDeliveries service){this.service=service;}
    @PostMapping("/api/integrations/notification-deliveries/claim") Map<String,Object> claim(@AuthenticationPrincipal IntegrationCredentials.Identity actor,@RequestBody JsonNode body){return service.claim(actor,body);}
    @PostMapping("/api/integrations/notification-deliveries/{id}/result") Map<String,Object> result(@AuthenticationPrincipal IntegrationCredentials.Identity actor,@PathVariable String id,@RequestBody JsonNode body){return service.result(actor,id,body);}
    @GetMapping("/api/admin/automation") Map<String,Object> state(){return service.state();}
    @PostMapping("/api/admin/automation/deliveries/{id}/review") Map<String,Object> review(@AuthenticationPrincipal SessionUser actor,@PathVariable String id,@RequestBody JsonNode body){return service.review(actor.identity().id(),id,body);}
}

@RestControllerAdvice(assignableTypes=AutomationController.class)
class AutomationErrors {
    @ExceptionHandler(NotificationDeliveries.Failure.class) void known(NotificationDeliveries.Failure e,HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,e.status,e.code,"请核对通知状态和配置");}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) void invalid(HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,400,"INVALID_ARGUMENT","请求参数不正确");}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) void db(HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,503,"DATABASE_UNAVAILABLE","暂时无法记录通知，请保留未知状态并核查");}
}

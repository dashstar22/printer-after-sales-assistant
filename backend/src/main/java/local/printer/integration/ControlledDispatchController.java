package local.printer.integration;

import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.*;
import local.printer.auth.SessionUser;
import local.printer.common.ApiResponses;
import local.printer.operations.ControlledDispatchGate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public class ControlledDispatchController {
    private final ControlledNotificationDispatch sender;private final ControlledDispatchGate gate;
    public ControlledDispatchController(ControlledNotificationDispatch sender,ControlledDispatchGate gate){this.sender=sender;this.gate=gate;}
    @PostMapping("/api/integrations/notification-deliveries/{id}/dispatch") public Map<String,Object> send(@AuthenticationPrincipal IntegrationCredentials.Identity actor,@PathVariable String id,@RequestBody JsonNode body){return sender.send(actor,id,body);}
    @GetMapping("/api/admin/controlled-dispatch") public Map<String,Object> state(){return gate.status();}
    @PostMapping("/api/admin/controlled-dispatch/receipt") public Map<String,Object> receipt(@AuthenticationPrincipal SessionUser actor,@RequestBody JsonNode body){
        NotificationDeliveries.fields(body,"attemptId","receiptReference");gate.review(NotificationDeliveries.text(body,"attemptId"),NotificationDeliveries.text(body,"receiptReference"),"admin:"+actor.identity().id());return gate.status();
    }
    @PostMapping("/api/admin/controlled-dispatch/stop") public Map<String,Object> stop(@AuthenticationPrincipal SessionUser actor){gate.stop("admin:"+actor.identity().id());return gate.status();}
}

@RestControllerAdvice(assignableTypes=ControlledDispatchController.class)
class ControlledDispatchErrors {
    @ExceptionHandler(ControlledDispatchGate.Denied.class) void denied(HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,409,"CONTROLLED_DISPATCH_DENIED","本批发送未获准或需要核查，请保持停止");}
    @ExceptionHandler(NotificationDeliveries.Failure.class) void delivery(NotificationDeliveries.Failure e,HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,e.status,e.code,"请核对通知领取和结果");}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) void invalid(HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,400,"INVALID_ARGUMENT","请求参数不正确");}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) void db(HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,503,"DATABASE_UNAVAILABLE","结果未能确认，请停止并核查");}
}

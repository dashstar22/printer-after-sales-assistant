package local.printer.auth.email;

import java.util.*;
import java.util.function.Supplier;
import jakarta.servlet.http.*;
import local.printer.auth.SessionUser;
import local.printer.common.ApiResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class EmailIdentityController {
    private final EmailIdentityService service;
    private final IdentityMailSender mail;
    private final EmailRateLimits limits;
    public EmailIdentityController(EmailIdentityService service,IdentityMailSender mail,EmailRateLimits limits){this.service=service;this.mail=mail;this.limits=limits;}
    @PostMapping("/api/auth/register") void register(@RequestBody JsonNode body,@AuthenticationPrincipal SessionUser user,HttpServletRequest req,HttpServletResponse res)throws java.io.IOException {
        anonymous(user);request("REGISTER",body,req,res);
    }
    @PostMapping("/api/auth/password-reset/request") void reset(@RequestBody JsonNode body,HttpServletRequest req,HttpServletResponse res)throws java.io.IOException {request("RESET",body,req,res);}
    private void request(String purpose,JsonNode b,HttpServletRequest req,HttpServletResponse res)throws java.io.IOException {
        limits.request(ip(req));mail.send(service.request(purpose,b));ApiResponses.write(req,res,202,EmailIdentityService.ACCEPTED);
    }
    @PostMapping("/api/auth/register/confirm") Map<String,Object> registerConfirm(@RequestBody JsonNode b,@AuthenticationPrincipal SessionUser user,HttpServletRequest req){anonymous(user);return consume(req,()->service.confirm("REGISTER",b,null));}
    @PostMapping("/api/auth/password-reset/confirm") Map<String,Object> resetConfirm(@RequestBody JsonNode b,HttpServletRequest req,HttpServletResponse res){var r=consume(req,()->service.confirm("RESET",b,null));clear(req,res);return r;}
    @PostMapping("/api/auth/invitations/accept") Map<String,Object> inviteAccept(@RequestBody JsonNode b,@AuthenticationPrincipal SessionUser user,HttpServletRequest req){anonymous(user);return consume(req,()->service.confirm("INVITE",b,null));}
    @GetMapping("/api/auth/migration") JsonNode migration(@AuthenticationPrincipal SessionUser user){return service.migration(user);}
    @PostMapping("/api/auth/email-bind/request") Map<String,Object> bindRequest(@RequestBody JsonNode b,@AuthenticationPrincipal SessionUser user,HttpServletRequest req){limits.request(ip(req));mail.send(service.bindRequest(user,b));return EmailIdentityService.ACCEPTED;}
    @PostMapping("/api/auth/email-bind/confirm") Map<String,Object> bindConfirm(@RequestBody JsonNode b,@AuthenticationPrincipal SessionUser user,HttpServletRequest req,HttpServletResponse res){var r=consume(req,()->service.confirm("BIND",b,user));clear(req,res);return r;}
    @GetMapping("/api/admin/email-invitations") JsonNode invitations(@AuthenticationPrincipal SessionUser actor,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="20")int pageSize){return service.invitations(actor,cursor,pageSize);}
    @PostMapping("/api/admin/email-invitations") JsonNode invite(@AuthenticationPrincipal SessionUser actor,@RequestBody JsonNode b,HttpServletRequest req){limits.request(ip(req));var result=service.invite(actor,b);mail.send(result.delivery());return result.result();}
    @PostMapping("/api/admin/email-invitations/{id}/resend") JsonNode resend(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody JsonNode b,HttpServletRequest req){limits.request(ip(req));var result=service.invitationAction(actor,id,b,false);mail.send(result.delivery());return result.result();}
    @PostMapping("/api/admin/email-invitations/{id}/revoke") JsonNode revoke(@AuthenticationPrincipal SessionUser actor,@PathVariable long id,@RequestBody JsonNode b){return service.invitationAction(actor,id,b,true).result();}
    @GetMapping("/api/admin/email-migration/preflight") JsonNode preflight(@AuthenticationPrincipal SessionUser actor){return service.preflight(actor);}
    private <T>T consume(HttpServletRequest req,Supplier<T> action){String key=ip(req);limits.consumption(key);try{return action.get();}catch(EmailFailure f){if(f.status<500)limits.failed(key);throw f;}}
    private String ip(HttpServletRequest req){return EmailIdentityService.digest(req.getRemoteAddr());}
    private void anonymous(SessionUser u){if(u!=null)throw new EmailFailure(409,"ALREADY_SIGNED_IN","请先退出当前账号");}
    private void clear(HttpServletRequest req,HttpServletResponse res){
        var session=req.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();
        for(String name:List.of("JSESSIONID","PRINTER_REMEMBER")){var c=new Cookie(name,"");c.setPath("/");c.setMaxAge(0);c.setHttpOnly(true);c.setSecure(req.isSecure());c.setAttribute("SameSite","Lax");res.addCookie(c);}
    }
}

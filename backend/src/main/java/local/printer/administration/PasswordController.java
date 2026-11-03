package local.printer.administration;

import local.printer.auth.SessionUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class PasswordController {
    private final AccountAdministration accounts;
    public PasswordController(AccountAdministration accounts){this.accounts=accounts;}
    @PostMapping("/api/auth/password") JsonNode change(@AuthenticationPrincipal SessionUser user,@RequestBody JsonNode body,
            jakarta.servlet.http.HttpServletRequest req,jakarta.servlet.http.HttpServletResponse res){
        JsonNode result=accounts.change(user,body);
        var session=req.getSession(false);if(session!=null)session.invalidate();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        for(String name:java.util.List.of("JSESSIONID","PRINTER_REMEMBER")) {
            var cookie=new jakarta.servlet.http.Cookie(name,"");cookie.setPath("/");cookie.setMaxAge(0);cookie.setHttpOnly(true);
            cookie.setSecure(req.isSecure());cookie.setAttribute("SameSite","Lax");res.addCookie(cookie);
        }
        return result;
    }
}

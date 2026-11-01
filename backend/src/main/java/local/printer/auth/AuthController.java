package local.printer.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {
    public record CsrfResponse(String headerName, String parameterName, String token) {}

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken csrf) {
        return new CsrfResponse(csrf.getHeaderName(), csrf.getParameterName(), csrf.getToken());
    }

    @GetMapping("/me")
    SessionUser.Identity me(@AuthenticationPrincipal SessionUser user) { return user.identity(); }
}

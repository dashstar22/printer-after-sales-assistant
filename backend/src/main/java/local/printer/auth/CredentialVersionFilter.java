package local.printer.auth;

import java.io.IOException;
import java.util.Set;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import local.printer.common.ApiResponses;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after session/remember-me restoration, before authorization, on every protected request. */
final class CredentialVersionFilter extends OncePerRequestFilter {
    private final AuthUserMapper users;
    private final local.printer.auth.email.EmailIdentityService emails;
    CredentialVersionFilter(AuthUserMapper users,local.printer.auth.email.EmailIdentityService emails){this.users=users;this.emails=emails;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof SessionUser user) {
            try {
                var current=users.findByUsername(user.getUsername());
                if(current==null || !"ACTIVE".equals(current.status()) || current.id()!=user.identity().id()
                    || current.credentialVersion()!=user.credentialVersion() || !current.role().equals(user.identity().role())
                    || (!current.emailVerified() && (current.recoveryPending()?!current.recoveryActive():!emails.migrationEnabled()))) {
                    SecurityContextHolder.clearContext();var session=req.getSession(false);if(session!=null)session.invalidate();
                    ApiResponses.error(req,res,401,"UNAUTHENTICATED","凭证已失效，请重新登录");return;
                }
                String path=req.getServletPath();
                // The /app identity forms import this shared request module. It is a fixed public
                // static resource, including after reload during binding or forced password change.
                boolean identityAsset=("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod())) && "/repair/api.js".equals(path);
                if(!current.emailVerified() && !Set.of("/api/auth/me","/api/auth/csrf","/api/auth/migration","/api/auth/password","/api/auth/logout",
                    "/api/auth/email-bind/request","/api/auth/email-bind/confirm").contains(path) && !path.startsWith("/app/") && !path.equals("/app") && !identityAsset) {
                    ApiResponses.error(req,res,403,"EMAIL_BIND_REQUIRED","请先以当前密码登录并验证绑定邮箱");return;
                }
                if(!current.emailVerified() && !user.passwordAuthenticated() && Set.of("/api/auth/password","/api/auth/email-bind/request","/api/auth/email-bind/confirm").contains(path)) {
                    ApiResponses.error(req,res,403,"PASSWORD_LOGIN_REQUIRED","请先使用当前密码重新登录");return;
                }
                if(current.mustChangePassword() && !Set.of("/api/auth/me","/api/auth/csrf","/api/auth/password","/api/auth/logout").contains(path)
                    && !path.startsWith("/app/") && !path.equals("/app") && !identityAsset) {
                    ApiResponses.error(req,res,403,"PASSWORD_CHANGE_REQUIRED","请先修改临时密码");return;
                }
            }catch(DataAccessException e){ApiResponses.error(req,res,503,"DATABASE_UNAVAILABLE","暂时无法核验身份");return;}
        }
        chain.doFilter(req,res);
    }
}

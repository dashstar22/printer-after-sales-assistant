package local.printer.operations;

import java.io.IOException;
import java.util.Objects;
import java.time.Duration;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import local.printer.auth.SessionUser;
import local.printer.common.ApiResponses;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RequestProtectionFilter extends OncePerRequestFilter {
    private final RedisProtection limits;private final Environment env;
    public RequestProtectionFilter(RedisProtection limits,Environment env){this.limits=limits;this.env=env;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        if("POST".equals(req.getMethod())){
            boolean allowed=true;String ip=req.getRemoteAddr(); // Never trust forwarded headers.
            if("/api/auth/login".equals(req.getServletPath())){
                int max=env.getProperty("LOGIN_REQUESTS_PER_MINUTE",Integer.class,20);
                allowed=limits.allow("login-ip",ip,max,Duration.ofMinutes(1));
                String email=Objects.toString(req.getParameter("username"),"").strip().toLowerCase(java.util.Locale.ROOT);
                allowed=limits.allow("login-email",email,max,Duration.ofMinutes(1))&&allowed;
            }else if(req.getServletPath().matches("/api/conversations/[^/]+/messages")){
                var auth=SecurityContextHolder.getContext().getAuthentication();
                if(auth!=null&&auth.getPrincipal() instanceof SessionUser user){
                    int max=env.getProperty("AI_REQUESTS_PER_MINUTE",Integer.class,12);
                    allowed=limits.allow("ai-user",Long.toString(user.identity().id()),max,Duration.ofMinutes(1));
                    allowed=limits.allow("ai-ip",ip,env.getProperty("AI_IP_REQUESTS_PER_MINUTE",Integer.class,max*2),Duration.ofMinutes(1))&&allowed;
                }
            }
            res.setHeader("X-Protection-State",limits.state());
            if(!allowed){res.setHeader("Retry-After","60");ApiResponses.error(req,res,429,"REQUEST_RATE_LIMITED","请求过于频繁，请稍后重试");return;}
        }
        chain.doFilter(req,res);
    }
}

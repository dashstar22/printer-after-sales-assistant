package local.printer.administration;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import local.printer.common.ApiResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+11)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class AdministrationTraceFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest req){return !req.getRequestURI().startsWith("/api/admin/") && !req.getRequestURI().equals("/api/auth/password");}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        res.setHeader("X-Trace-Id",ApiResponses.traceId(req));res.setHeader("Cache-Control","no-store");chain.doFilter(req,res);
    }
}

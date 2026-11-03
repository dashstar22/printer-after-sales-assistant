package local.printer.auth.email;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import local.printer.common.ApiResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE+10)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class EmailTraceFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getRequestURI().startsWith("/api/auth/");}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        res.setHeader("X-Trace-Id",ApiResponses.traceId(req));res.setHeader("Cache-Control","no-store");res.setHeader("Referrer-Policy","no-referrer");chain.doFilter(req,res);
    }
}

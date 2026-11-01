package local.printer.assistant;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import local.printer.common.ApiResponses;
import local.printer.config.DependencyProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在Security之前创建服务端追踪号，拒绝请求也有同一条关联记录。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class AssistantTraceFilter extends OncePerRequestFilter {
    private final DependencyProperties config;
    private final String declaredMode;
    public AssistantTraceFilter(DependencyProperties config,Environment env){
        this.config=config;this.declaredMode=env.getProperty("ASSISTANT_MODEL_MODE","AUTO");
        if(!Set.of("AUTO","PROTOCOL_SIMULATION").contains(declaredMode))throw new IllegalArgumentException("ASSISTANT_MODEL_MODE_INVALID");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String path=request.getRequestURI();return !(path.equals("/api/conversations")||path.startsWith("/api/conversations/")||path.startsWith("/api/ticket-operations/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        // ApiResponses只读服务端request attribute；不采信X-Trace-Id/traceparent或正文。
        String trace=ApiResponses.traceId(request);response.setHeader("X-Trace-Id",trace);
        var run=AssistantAudit.open(trace,operation(request),modelMode(config.ai(),declaredMode));boolean failed=true;
        try {chain.doFilter(request,response);failed=false;}
        finally {try {run.finish(response.getStatus(),(String)request.getAttribute(ApiResponses.ERROR_CODE),failed);}finally{AssistantAudit.clear();}}
    }
    static String operation(HttpServletRequest request){
        String path=request.getRequestURI();
        if(path.startsWith("/api/ticket-operations/"))return path.endsWith("/execute")?"ORDINARY_TICKET_EXECUTE":"OPERATION_READ";
        if(path.endsWith("/execute"))return "CONSULTATION_TICKET_EXECUTE";
        if(path.endsWith("/messages"))return "MESSAGE";
        return request.getMethod().equals("POST")?"CONVERSATION_CREATE":"CONVERSATION_READ";
    }
    public static String modelMode(DependencyProperties.Ai ai,String declaredMode){
        if(!ai.enabled())return "DISABLED";
        String host=ai.baseUrl()==null?null:ai.baseUrl().getHost();
        if(host==null)return "UNCONFIGURED";
        boolean local=Set.of("127.0.0.1","localhost","[::1]").contains(host);
        if(local&&declaredMode.equals("PROTOCOL_SIMULATION"))return "PROTOCOL_SIMULATION";
        return local?"LOCAL_API_UNVERIFIED":"REMOTE_API_UNVERIFIED";
    }
}

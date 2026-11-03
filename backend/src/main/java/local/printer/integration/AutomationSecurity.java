package local.printer.integration;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import local.printer.common.ApiResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class AutomationSecurity {
    @Bean @Order(1) SecurityFilterChain automationChain(HttpSecurity http,AutomationSettings settings,IntegrationCredentials credentials)throws Exception {
        return http.securityMatcher("/mcp","/mcp/**","/api/integrations/**")
            .csrf(c->c.disable()).cors(c->c.disable()).requestCache(c->c.disable())
            .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(c->c.requireExplicitSave(true))
            .authorizeHttpRequests(c->c.anyRequest().authenticated())
            .addFilterAfter(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
                    SecurityContextHolder.clearContext(); // No inherited cookie/remember-me identity on machine endpoints.
                    res.setHeader("Cache-Control","no-store");
                    if(!settings.enabled){ApiResponses.error(req,res,503,"AUTOMATION_DISABLED","自动化未启用");return;}
                    // Exact authority; forwarded headers are deliberately not trusted here.
                    String host=req.getHeader("Host"),origin=req.getHeader("Origin");
                    if(host==null || !settings.hosts.contains(host) || (origin!=null && !settings.origins.contains(origin))){
                        ApiResponses.error(req,res,403,"AUTOMATION_ORIGIN_DENIED","服务地址或来源未获准");return;
                    }
                    String auth=req.getHeader("Authorization");
                    try {
                        var identity=credentials.authenticate(auth!=null && auth.startsWith("Bearer ")?auth.substring(7):null);
                        if(identity==null){ApiResponses.error(req,res,401,"AUTOMATION_UNAUTHENTICATED","服务凭证无效");return;}
                        String scope=req.getRequestURI().startsWith("/mcp")?"automation:read":"notification:write";
                        if(!identity.scopes().contains(scope)){ApiResponses.error(req,res,403,"AUTOMATION_SCOPE_DENIED","服务权限不足");return;}
                        req.setAttribute(IntegrationCredentials.Identity.class.getName(),identity);
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of(new SimpleGrantedAuthority(scope))));
                        chain.doFilter(req,res);
                    }catch(DataAccessException unavailable){ApiResponses.error(req,res,503,"DATABASE_UNAVAILABLE","暂时无法核验自动化身份");}
                    finally{SecurityContextHolder.clearContext();}
                }
            },SecurityContextHolderFilter.class).build();
    }
}

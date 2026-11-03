package local.printer.integration;

import java.util.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class AutomationMcp {
    @Bean ServletRegistrationBean<HttpServletStatelessServerTransport> automationMcpServlet(AutomationQueries queries,JdbcTemplate db){
        var transport=HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").maxRequestSize(16384)
            .contextExtractor(req->io.modelcontextprotocol.common.McpTransportContext.create(Map.of("identity",req.getAttribute(IntegrationCredentials.Identity.class.getName())))).build();
        var server=McpServer.sync(transport).serverInfo("printer-service-automation","1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
        for(String name:List.of("list_overdue_tickets","get_service_summary")){
            Map<String,Object> properties=name.equals("list_overdue_tickets")?Map.of(
                "cursor",Map.of("type","integer","minimum",0),"pageSize",Map.of("type","integer","minimum",1,"maximum",100),"asOf",Map.of("type","string","format","date-time")):
                Map.of("date",Map.of("type","string","format","date"));
            var tool=McpSchema.Tool.builder().name(name).description(name.equals("list_overdue_tickets")?"只读查询超时工单，按游标分页；无联系方式或咨询正文":"Asia/Shanghai 过去31天某日新建工单的当前状态计数；默认前一自然日")
                .inputSchema(Map.of("type","object","properties",properties,"additionalProperties",false)).build();
            server.toolCall(tool,(context,request)->{
                // SDK dispatches handlers on its executor; propagate only the filter-verified immutable identity.
                if(!(context.get("identity") instanceof IntegrationCredentials.Identity identity))return result(Map.of("code","AUTOMATION_UNAUTHENTICATED"),true);
                Object payload;String status="OK";boolean error=false;
                try{payload=name.equals("list_overdue_tickets")?queries.overdue(request.arguments()):queries.summary(request.arguments());}
                catch(IllegalArgumentException|ClassCastException e){payload=Map.of("code","INVALID_ARGUMENT");status="INVALID_ARGUMENT";error=true;}
                catch(org.springframework.dao.DataAccessException e){payload=Map.of("code","DATABASE_UNAVAILABLE");status="DATABASE_UNAVAILABLE";error=true;}
                db.update("INSERT INTO automation_runs(credential_id,tool_name,result) VALUES (?,?,?)",identity.id(),name,status);
                return result(payload,error);
            });
        }
        server.build();return new ServletRegistrationBean<>(transport,"/mcp");
    }
    private static McpSchema.CallToolResult result(Object data,boolean error){return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(new ObjectMapper().writeValueAsString(data))),error,null,null);}
}

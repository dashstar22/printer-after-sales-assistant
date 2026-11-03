package local.printer.integration;

import java.util.Map;
import org.springframework.boot.*;

/** Operator supplies a locally generated secret through the environment, never argv or stdout. */
public final class AutomationCredentialCommand {
    public static int run(Class<?> source,String[] args){
        if(args.length!=1 || !java.util.Set.of("--automation-credential-create","--automation-credential-revoke").contains(args[0]))return 2;
        var app=new SpringApplication(source);app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of("logging.level.root","OFF","spring.main.banner-mode","off"));app.setLogStartupInfo(false);
        try(var ctx=app.run()){
            var env=ctx.getEnvironment();var service=ctx.getBean(IntegrationCredentials.class);
            String id=env.getRequiredProperty("AUTOMATION_CREDENTIAL_ID");
            if(args[0].endsWith("create"))service.create(id,env.getRequiredProperty("AUTOMATION_CREDENTIAL_LABEL"),env.getRequiredProperty("AUTOMATION_CREDENTIAL_SCOPES"),env.getRequiredProperty("AUTOMATION_CREDENTIAL_SECRET"));
            else service.revoke(id);
            System.out.println("AUTOMATION_CREDENTIAL_OK "+id);return 0;
        }catch(RuntimeException e){System.err.println("AUTOMATION_CREDENTIAL_FAILED");return 2;}
    }
}

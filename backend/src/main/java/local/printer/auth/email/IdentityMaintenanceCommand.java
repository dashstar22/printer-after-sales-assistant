package local.printer.auth.email;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Offline only; env secrets never appear as command-line arguments or in failure diagnostics. */
public final class IdentityMaintenanceCommand {
    private IdentityMaintenanceCommand(){}
    public static int run(Class<?> source,String[] args) {
        if(args.length!=1 || !java.util.Set.of("--identity-maintenance=recover","--identity-maintenance=invalidate-restored").contains(args[0])){System.err.println("IDENTITY_COMMAND_INVALID");return 2;}
        var app=new SpringApplication(source);app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of("logging.level.root","OFF","spring.main.banner-mode","off"));app.setLogStartupInfo(false);
        try(var ctx=app.run()) {
            var env=ctx.getEnvironment();var service=ctx.getBean(EmailIdentityService.class);
            String operator=env.getProperty("IDENTITY_OPERATOR_REFERENCE"),verification=env.getProperty("IDENTITY_VERIFICATION_REFERENCE");
            if(args[0].endsWith("=recover"))service.maintenanceRecover(Long.parseLong(env.getRequiredProperty("IDENTITY_RECOVERY_USER_ID")),env.getProperty("IDENTITY_RECOVERY_PASSWORD"),operator,verification);
            else service.invalidateRestored(operator,verification);
            System.out.println("IDENTITY_MAINTENANCE_COMPLETED");return 0;
        }catch(RuntimeException e){System.err.println("IDENTITY_MAINTENANCE_FAILED");return 2;}
    }
}

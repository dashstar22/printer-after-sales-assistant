package local.printer.administration;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Explicit local bootstrap. Password is never accepted as a command-line argument. */
public final class AdministratorCommand {
    private AdministratorCommand(){}
    public static int run(Class<?> source,String[] args) {
        if(args.length!=1 || !args[0].equals("--initialize-admin")){System.err.println("ADMIN_COMMAND_INVALID");return 2;}
        var app=new SpringApplication(source);app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of("logging.level.root","OFF","spring.main.banner-mode","off"));app.setLogStartupInfo(false);
        try(var ctx=app.run()) {
            var env=ctx.getEnvironment();
            ctx.getBean(AccountAdministration.class).initialize(env.getProperty("ADMIN_INIT_USERNAME"),env.getProperty("ADMIN_INIT_PASSWORD"));
            System.out.println("ADMIN_INITIALIZED");return 0;
        }catch(RuntimeException e){
            for(Throwable c=e;c!=null;c=c.getCause())if(c instanceof AdminFailure f){System.err.println(f.code);return 2;}
            String detail="";
            for(Throwable c=e;c!=null;c=c.getCause()) {
                detail+=" "+c.getClass().getSimpleName();
                for(var frame:c.getStackTrace())if(frame.getClassName().startsWith("local.printer.")){detail+="@"+frame.getClassName()+":"+frame.getLineNumber();break;}
                if(c instanceof java.sql.SQLException sql)detail+=" SQLSTATE="+sql.getSQLState()+" vendor="+sql.getErrorCode();
            }
            System.err.println("ADMIN_INITIALIZATION_FAILED"+detail);return 3;
        }
    }
}

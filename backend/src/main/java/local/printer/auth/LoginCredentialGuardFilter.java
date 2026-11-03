package local.printer.auth;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import javax.sql.DataSource;
import local.printer.common.ApiResponses;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/** Serialize password authentication AND persistent-token issuance with credential revocation. */
final class LoginCredentialGuardFilter extends OncePerRequestFilter {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    LoginCredentialGuardFilter(DataSource source,PlatformTransactionManager manager){db=new JdbcTemplate(source);tx=new TransactionTemplate(manager);}
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getMethod().equals("POST") || !r.getServletPath().equals("/api/auth/login");}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException {
        try {
            tx.executeWithoutResult(status->{
                db.queryForObject("SELECT id FROM administration_guard WHERE id=1 FOR UPDATE",Integer.class);
                try{chain.doFilter(req,res);}catch(IOException|ServletException e){throw new ChainFailure(e);}
            });
        }catch(ChainFailure e){if(e.getCause() instanceof IOException io)throw io;throw (ServletException)e.getCause();}
        catch(DataAccessException unavailable){ApiResponses.error(req,res,503,"DATABASE_UNAVAILABLE","暂时无法核验身份");}
    }
    private static final class ChainFailure extends RuntimeException {ChainFailure(Exception e){super(e);}}
}

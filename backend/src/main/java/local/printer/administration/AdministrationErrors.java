package local.printer.administration;

import java.io.IOException;
import jakarta.servlet.http.*;
import local.printer.auth.SessionUser;
import local.printer.common.ApiResponses;
import org.springframework.dao.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice(assignableTypes={AdministrationController.class,PasswordController.class,local.printer.knowledge.KnowledgeManagementController.class,local.printer.jobs.KnowledgeJobController.class})
public class AdministrationErrors {
    private final AdminLedger ledger;
    public AdministrationErrors(AdminLedger ledger){this.ledger=ledger;}
    @ExceptionHandler(AdminFailure.class) void known(AdminFailure f,HttpServletRequest req,HttpServletResponse res)throws IOException {error(req,res,f.status,f.code,f.getMessage());}
    @ExceptionHandler(DuplicateKeyException.class) void duplicate(HttpServletRequest req,HttpServletResponse res)throws IOException {error(req,res,409,"UNIQUE_CONFLICT","账号、型号别名或订单编号已存在");}
    @ExceptionHandler(local.printer.knowledge.KnowledgeFailure.class)
    void knowledge(local.printer.knowledge.KnowledgeFailure f,HttpServletRequest req,HttpServletResponse res)throws IOException {
        error(req,res,f.exitCode()==4?409:400,f.getMessage(),"资料登记失败，请核对来源与版本");
    }
    @ExceptionHandler(DataAccessException.class) void db(HttpServletRequest req,HttpServletResponse res)throws IOException {error(req,res,503,"DATABASE_UNAVAILABLE","暂时无法完成操作，请保留输入后重试");}
    @ExceptionHandler(MaxUploadSizeExceededException.class) void size(HttpServletRequest req,HttpServletResponse res)throws IOException {error(req,res,413,"RESOURCE_LIMIT","上传体积超限");}
    @ExceptionHandler({HttpMessageNotReadableException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    void invalid(HttpServletRequest req,HttpServletResponse res)throws IOException {error(req,res,400,"INVALID_ARGUMENT","请求参数不正确");}
    private void error(HttpServletRequest req,HttpServletResponse res,int status,String code,String message)throws IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof SessionUser u && !req.getMethod().equals("GET")) {
            try{ledger.rejected(u.identity().id(),req.getRequestURI().equals("/api/auth/password")?"PASSWORD":"ADMINISTRATION",code);}
            catch(DataAccessException unavailable){/* The original controlled failure remains authoritative. */}
        }
        ApiResponses.error(req,res,status,code,message);
    }
}

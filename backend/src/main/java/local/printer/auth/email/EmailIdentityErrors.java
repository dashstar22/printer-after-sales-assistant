package local.printer.auth.email;

import java.io.IOException;
import jakarta.servlet.http.*;
import local.printer.administration.AdminFailure;
import local.printer.common.ApiResponses;
import org.springframework.dao.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=EmailIdentityController.class)
public class EmailIdentityErrors {
    @ExceptionHandler(EmailFailure.class) void email(EmailFailure e,HttpServletRequest req,HttpServletResponse res)throws IOException {
        if(e.status==429)res.setHeader("Retry-After","900");ApiResponses.error(req,res,e.status,e.code,e.getMessage());
    }
    @ExceptionHandler(AdminFailure.class) void admin(AdminFailure e,HttpServletRequest req,HttpServletResponse res)throws IOException {ApiResponses.error(req,res,e.status,e.code,e.getMessage());}
    @ExceptionHandler(DuplicateKeyException.class) void duplicate(HttpServletRequest req,HttpServletResponse res)throws IOException {ApiResponses.error(req,res,409,"EMAIL_CONFLICT","邮箱或请求已被使用，请刷新核对");}
    @ExceptionHandler(DataAccessException.class) void db(HttpServletRequest req,HttpServletResponse res)throws IOException {ApiResponses.error(req,res,503,"DATABASE_UNAVAILABLE","暂时无法完成邮箱操作，请稍后重试");}
    @ExceptionHandler({HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    void invalid(HttpServletRequest req,HttpServletResponse res)throws IOException {ApiResponses.error(req,res,400,"EMAIL_ARGUMENT_INVALID","请求字段不正确");}
}

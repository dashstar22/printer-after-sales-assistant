package local.printer.auth;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import local.printer.common.ApiResponses;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = RegistrationController.class)
public class RegistrationErrors {
    @ExceptionHandler(RegistrationService.Invalid.class)
    void invalid(RegistrationService.Invalid error, HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req, res, 400, "REGISTRATION_INVALID", error.getMessage());
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    void unreadable(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req, res, 400, "REGISTRATION_INVALID", "注册信息格式不正确");
    }
    @ExceptionHandler(RegistrationService.AccountExists.class)
    void exists(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req, res, 409, "ACCOUNT_EXISTS", "该账号已被使用，请更换账号或返回登录");
    }
    @ExceptionHandler(RegistrationController.AlreadySignedIn.class)
    void signedIn(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req, res, 409, "ALREADY_SIGNED_IN", "当前已登录，请先退出再注册新账号");
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    void media(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req, res, 415, "UNSUPPORTED_MEDIA_TYPE", "请使用注册表单提交");
    }
    @ExceptionHandler(DataAccessException.class)
    void unavailable(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req, res, 503, "REGISTRATION_UNAVAILABLE", "暂时无法完成注册，请保留填写内容后重试");
    }
}

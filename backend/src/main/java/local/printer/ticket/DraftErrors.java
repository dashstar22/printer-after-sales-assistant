package local.printer.ticket;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import local.printer.common.ApiResponses;
import local.printer.order.OrderService;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {DraftController.class, ConfirmationController.class})
public class DraftErrors {
    @ExceptionHandler(ConfirmationService.Conflict.class)
    void confirmationConflict(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 409, "CONFIRMATION_CONFLICT", "确认与当前草稿或工单不匹配");
    }
    @ExceptionHandler(DraftService.NotFound.class)
    void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 404, "DRAFT_NOT_FOUND", "未找到报修草稿");
    }
    @ExceptionHandler(OrderService.NotFound.class)
    void orderNotFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 404, "ORDER_NOT_FOUND", "未找到订单");
    }
    @ExceptionHandler({DraftService.InvalidInput.class, HttpMessageNotReadableException.class})
    void invalid(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 400, "INVALID_ARGUMENT", "请求参数不合法");
    }
    @ExceptionHandler({DraftService.Conflict.class, CannotAcquireLockException.class})
    void conflict(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 409, "DRAFT_CONFLICT", "草稿已变化，请刷新后重试");
    }
    @ExceptionHandler(DraftService.AlreadySubmitted.class)
    void submitted(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 409, "DRAFT_ALREADY_SUBMITTED", "草稿已关联工单，不能修改");
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    void unsupported(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 415, "UNSUPPORTED_MEDIA_TYPE", "请使用JSON请求");
    }
    @ExceptionHandler(DataAccessException.class)
    void unavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 503, "DATABASE_UNAVAILABLE", "暂时无法访问报修草稿");
    }
}

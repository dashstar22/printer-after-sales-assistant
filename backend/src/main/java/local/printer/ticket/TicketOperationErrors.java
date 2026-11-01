package local.printer.ticket;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import local.printer.common.ApiResponses;
import local.printer.assistant.ConversationTicketController;
import local.printer.assistant.ConversationService;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {TicketOperationController.class, ConversationTicketController.class})
public class TicketOperationErrors {
    @ExceptionHandler(ConversationService.NotFound.class)
    void conversationMissing(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request,response,404,"CONVERSATION_NOT_FOUND","未找到咨询会话");
    }
    @ExceptionHandler({TicketOperationService.NotFound.class, ConfirmationService.NotFound.class})
    void missing(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 404, "OPERATION_NOT_FOUND", "未找到报修操作");
    }
    @ExceptionHandler({ConfirmationService.Conflict.class, DraftService.NotFound.class})
    void binding(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 409, "CONFIRMATION_CONFLICT", "确认与当前草稿或工单不匹配");
    }
    @ExceptionHandler(ConfirmationService.Expired.class)
    void expired(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 409, "CONFIRMATION_EXPIRED", "确认已过期，请重新核对并确认");
    }
    @ExceptionHandler({TicketOperationService.Conflict.class, CannotAcquireLockException.class})
    void conflict(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 409, "OPERATION_CONFLICT", "操作冲突，请查询原操作结果");
    }
    @ExceptionHandler({DraftService.InvalidInput.class, ConversationService.InvalidInput.class, HttpMessageNotReadableException.class})
    void invalid(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 400, "INVALID_ARGUMENT", "请求参数不合法");
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    void unsupported(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 415, "UNSUPPORTED_MEDIA_TYPE", "请使用JSON请求");
    }
    @ExceptionHandler(DataAccessException.class)
    void unavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 503, "DATABASE_UNAVAILABLE", "报修操作结果暂时无法确认；请保留原operationId查询恢复，勿换标识重复建单");
    }
}

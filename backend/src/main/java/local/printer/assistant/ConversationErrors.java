package local.printer.assistant;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import local.printer.common.ApiResponses;
import local.printer.order.OrderService;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = ConversationController.class)
public class ConversationErrors {
    @ExceptionHandler(ConversationService.NotFound.class)
    void missing(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req,res,404,"CONVERSATION_NOT_FOUND","未找到咨询会话");
    }
    @ExceptionHandler(OrderService.NotFound.class)
    void orderMissing(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req,res,404,"ORDER_NOT_FOUND","未找到订单");
    }
    @ExceptionHandler({ConversationService.InvalidInput.class, HttpMessageNotReadableException.class})
    void invalid(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req,res,400,"INVALID_ARGUMENT","请求参数不合法");
    }
    @ExceptionHandler({ConversationService.Conflict.class, CannotAcquireLockException.class})
    void conflict(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req,res,409,"CONVERSATION_CONFLICT","消息提交冲突，请读取原咨询并核对原turnId后重试");
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    void unsupported(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req,res,415,"UNSUPPORTED_MEDIA_TYPE","请使用JSON请求");
    }
    @ExceptionHandler(DataAccessException.class)
    void unavailable(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ApiResponses.error(req,res,503,"DATABASE_UNAVAILABLE","暂时无法访问咨询消息，请保留原turnId并查询后重试");
    }
}

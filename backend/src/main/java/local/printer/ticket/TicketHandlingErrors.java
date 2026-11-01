package local.printer.ticket;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import local.printer.common.ApiResponses;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TicketHandlingController.class)
public class TicketHandlingErrors {
    @ExceptionHandler(TicketQueryService.NotFound.class)
    void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 404, "TICKET_NOT_FOUND", "未找到工单");
    }
    @ExceptionHandler({DraftService.InvalidInput.class, HttpMessageNotReadableException.class})
    void invalid(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 400, "INVALID_ARGUMENT", "请求参数不合法");
    }
    @ExceptionHandler({TicketHandlingService.Conflict.class, CannotAcquireLockException.class})
    void conflict(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 409, "TICKET_CONFLICT", "工单已变化，请刷新后重试");
    }
    @ExceptionHandler({TicketHandlingController.UnsupportedMedia.class, HttpMediaTypeNotSupportedException.class})
    void unsupported(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 415, "UNSUPPORTED_MEDIA_TYPE", "请使用JSON请求");
    }
    @ExceptionHandler(DataAccessException.class)
    void unavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 503, "DATABASE_UNAVAILABLE", "暂时无法处理工单");
    }
}

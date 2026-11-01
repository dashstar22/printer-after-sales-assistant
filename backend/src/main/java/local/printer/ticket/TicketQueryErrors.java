package local.printer.ticket;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import local.printer.common.ApiResponses;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TicketQueryController.class)
public class TicketQueryErrors {
    @ExceptionHandler(TicketQueryService.NotFound.class)
    void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 404, "TICKET_NOT_FOUND", "未找到工单");
    }
    @ExceptionHandler(DraftService.InvalidInput.class)
    void invalid(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 400, "INVALID_ARGUMENT", "请求参数不合法");
    }
    @ExceptionHandler(DataAccessException.class)
    void unavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 503, "DATABASE_UNAVAILABLE", "暂时无法查询工单");
    }
}

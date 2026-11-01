package local.printer.order;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import local.printer.common.ApiResponses;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 只覆盖已实现的订单读取，复用过滤器层相同的 JSON/traceId 写入器。 */
@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderErrors {
    @ExceptionHandler(OrderService.NotFound.class)
    void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 404, "ORDER_NOT_FOUND", "未找到订单");
    }
    @ExceptionHandler(OrderService.InvalidInput.class)
    void invalid(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 400, "INVALID_ARGUMENT", "请求参数不合法");
    }
    @ExceptionHandler(DataAccessException.class)
    void unavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(request, response, 503, "DATABASE_UNAVAILABLE", "暂时无法查询订单");
    }
}

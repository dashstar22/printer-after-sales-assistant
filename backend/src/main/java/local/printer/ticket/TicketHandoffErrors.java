package local.printer.ticket;

import java.io.IOException;
import jakarta.servlet.http.*;
import local.printer.common.ApiResponses;
import org.springframework.dao.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=TicketHandoffController.class)
public class TicketHandoffErrors {
    @ExceptionHandler(TicketHandoffService.Failure.class)
    void known(TicketHandoffService.Failure f,HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,f.status,f.code,f.getMessage());}
    @ExceptionHandler({DraftService.InvalidInput.class,org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    void invalid(HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,400,"INVALID_ARGUMENT","请求参数不合法");}
    @ExceptionHandler(CannotAcquireLockException.class)
    void conflict(HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,409,"TICKET_CONFLICT","工单已变化，请刷新后重试");}
    @ExceptionHandler(DataAccessException.class)
    void db(HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,503,"DATABASE_UNAVAILABLE","结果暂时无法确认，请保留原请求标识重试或查回");}
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    void type(HttpServletRequest req,HttpServletResponse res)throws IOException{ApiResponses.error(req,res,415,"UNSUPPORTED_MEDIA_TYPE","请求类型不受支持");}
}

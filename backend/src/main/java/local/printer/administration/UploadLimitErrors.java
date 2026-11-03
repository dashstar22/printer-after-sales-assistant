package local.printer.administration;

import java.io.IOException;
import jakarta.servlet.http.*;
import local.printer.common.ApiResponses;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Multipart parsing can fail before a handler/controller is selected. */
@RestControllerAdvice
public class UploadLimitErrors {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    void limit(HttpServletRequest req,HttpServletResponse res)throws IOException {
        ApiResponses.error(req,res,413,"RESOURCE_LIMIT","上传体积超限，请缩小文件后重试");
    }
}

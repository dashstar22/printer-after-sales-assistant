package local.printer.common;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

public final class ApiResponses {
    public static final String ERROR_CODE = ApiResponses.class.getName()+".errorCode";
    private static final ObjectMapper JSON = new ObjectMapper();
    private ApiResponses() {}

    public static String traceId(HttpServletRequest request) {
        Object id = request.getAttribute(ApiResponses.class.getName());
        if (id == null) {
            id = UUID.randomUUID().toString();
            request.setAttribute(ApiResponses.class.getName(), id);
        }
        return id.toString();
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Trace-Id", traceId(request));
        response.getWriter().write(JSON.writeValueAsString(body));
    }

    public static void error(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        request.setAttribute(ERROR_CODE, code);
        write(request, response, status, Map.of("code", code, "message", message, "traceId", traceId(request)));
    }
}

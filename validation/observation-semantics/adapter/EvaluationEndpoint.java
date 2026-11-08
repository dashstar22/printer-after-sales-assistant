package local.printer.assistant;

import java.net.URI;
import java.util.Map;

/** Test adapter boundary; never part of production routing or persistence. */
interface EvaluationEndpoint extends AutoCloseable {
    URI baseUrl();
    void expect(String id, String text);
    Map<String, Object> current();
    Map<String, Object> snapshot();
    default void halt() {}
    @Override void close();
}

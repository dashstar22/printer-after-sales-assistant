package local.printer.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 仅绑定配置。客户端及其运行时错误处理留待知识检索和助手任务实现。 */
@ConfigurationProperties("app.dependencies")
public record DependencyProperties(Qdrant qdrant, Ai ai) {
    public record Qdrant(URI baseUrl) {}
    public record Ai(boolean enabled, URI baseUrl, String chatModel, String apiKey) {
        @Override
        public String toString() {
            return "Ai[enabled=" + enabled + ", credentials=REDACTED]";
        }
    }
}

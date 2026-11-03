package local.printer.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgePurposeOptionsTest {
    @Test void emptyServerConfigurationDoesNotGrantAnyPurpose() {
        var available=new KnowledgePurposePolicy(new MockEnvironment()).availableConfigurations();
        assertEquals(java.util.Set.of("embedding","rerank","chat"),available.keySet());
        assertTrue(available.values().stream().allMatch(java.util.List::isEmpty));
    }
    @Test void onlySortedPurposeScopedConfigurationIdentitiesAreExposed() {
        var env=new MockEnvironment().withProperty("app.knowledge.processing-configurations",
            "chat:z,embedding:e,chat:a,chat:a,rerank:r,unknown:hidden");
        env.setProperty("KNOWLEDGE_EMBEDDING_API_KEY","sensitive-credential");
        env.setProperty("KNOWLEDGE_EMBEDDING_BASE_URL","https://private.example.invalid/path");
        var available=new KnowledgePurposePolicy(env).availableConfigurations();
        assertEquals(java.util.List.of("a","z"),available.get("chat"));
        assertEquals(java.util.List.of("e"),available.get("embedding"));
        assertEquals(java.util.List.of("r"),available.get("rerank"));
        assertFalse(available.toString().contains("sensitive"));
        assertFalse(available.toString().contains("https://"));
        assertFalse(available.toString().contains("hidden"));
    }
}

package local.printer.assistant;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class EditDiagnosticPersistenceTest {
    @TempDir Path directory;

    @ParameterizedTest @NullSource @ValueSource(booleans={true,false})
    void persistedCheckpointHasThreeStatesWithoutInventingANegative(Boolean saved) throws Exception {
        String turn=UUID.randomUUID().toString();
        new EditDiagnosticEvidence(directory.toString(),turn).outcome("EXECUTION_STOPPED","WRITE_RESULT_UNKNOWN",true,saved,1,1);
        var row=JsonMapper.builder().build().readTree(Files.readString(directory.resolve("edit").resolve(turn+"-1.json")));
        assertEquals("HOST_OUTCOME",row.path("phase").asString());
        assertEquals(saved==null?"UNKNOWN":saved?"SAVED":"NOT_SAVED",row.path("draftPersistence").asString());
        if(saved==null)assertTrue(row.path("draftSaved").isNull());
        else assertEquals(saved.booleanValue(),row.path("draftSaved").asBoolean());
        assertFalse(row.has("draftId"));assertFalse(row.has("arguments"));
    }
}

package local.printer.assistant;

import java.util.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObservedEvidenceV4Test {
    @Test void sqlFailuresRetainAuditAndContinueHttpAndSaveEveryStage()throws Exception {
        var stages=new ArrayList<Map<String,Object>>();var steps=new LinkedHashMap<String,ObservedEvidenceV4.Read>();
        steps.put("orderReads",()->List.of(Map.of("method","findOwned","customerHash","foreign")));
        steps.put("businessAfter",()->{throw new SQLException("must not enter evidence");});
        steps.put("sqlReply",()->{throw new SQLException("sensitive database detail");});
        steps.put("httpReadback",()->Map.of("status","TOOL_REJECTED"));
        var data=ObservedEvidenceV4.collect(new LinkedHashMap<>(),steps,row->stages.add(new LinkedHashMap<>(row)));
        assertEquals(5,stages.size());assertTrue(stages.get(1).containsKey("orderReads"));
        assertTrue(data.containsKey("httpReadback"));assertTrue(data.containsKey("orderReads"));
        assertEquals(Map.of("businessAfter","SQLException","sqlReply","SQLException"),data.get("observationErrors"));
        assertFalse(data.toString().contains("sensitive"));
    }
    @Test void failedRefreshKeepsPreviousSuccessfulObservation()throws Exception {
        var data=new LinkedHashMap<String,Object>();data.put("businessAfter",Map.of("tickets",1));
        var result=ObservedEvidenceV4.collect(data,Map.of("businessAfter",()->{throw new SQLException();}),row->{});
        assertEquals(Map.of("tickets",1),result.get("businessAfter"));
    }
    @Test void assertionInOneObservationDoesNotDiscardRemainingEvidence()throws Exception {
        var steps=new LinkedHashMap<String,ObservedEvidenceV4.Read>();
        steps.put("httpReadback",()->{throw new AssertionError();});steps.put("sqlReply",()->Map.of("status","ANSWERED"));
        var result=ObservedEvidenceV4.collect(new LinkedHashMap<>(),steps,row->{});
        assertEquals(Map.of("httpReadback","AssertionError"),result.get("observationErrors"));assertTrue(result.containsKey("sqlReply"));
    }
}

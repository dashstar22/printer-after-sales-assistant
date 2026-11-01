package local.printer.assistant;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static local.printer.assistant.ObservationContinuationTest.*;

class ObservationGuidanceTest {
    final ObservationContinuationTest fixture=new ObservationContinuationTest();
    @AfterEach void close(){fixture.close();}
    Object bound(List<ConversationMapper.Message> h,String current,String target)throws Exception{return fixture.bound(h,current,target);}
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"{\"kind\":\"NO_EVIDENCE\"}","{\"kind\":\"KNOWLEDGE\",\"pointIds\":[\"SECRET_MARKER\"]}","{citation:SECRET_MARKER}","createServiceTicket SECRET_MARKER"})
    void rejectionKeepsReasonAndAddsOnlyFixedGuidance(String output)throws Exception{
        var answer=finish(bound(history(GUESS,20,"NEEDS_INPUT"),GUESS,"NONE"),output);
        assertEquals("REFERENCE_REJECTED",answer.status());assertTrue(answer.text().startsWith(EvidenceAnswerPolicy.rejected().text()));
        assertTrue(answer.text().endsWith(NEW_ASK));assertFalse(answer.text().contains("SECRET_MARKER"));assertTrue(answer.citations().isEmpty());
        assertEquals(EvidenceAnswerPolicy.rejected(),finish(bound(List.of(),GUESS,"NONE"),output));
    }
    @Test void rejectedGuidanceDoesNotRestartPendingChain(){
        var h=history(GUESS,20,"REFERENCE_REJECTED");
        assertTrue(AssistantConversationService.pendingObservation(h,GUESS,true).isEmpty());
    }
    @Test void reasonCodesDistinguishTrustedStateFailure(){
        assertEquals("VALID",AssistantConversationService.observationState(history(GUESS,20,"NEEDS_INPUT"),GUESS,true).reason());
        assertEquals("TIME_LIMIT",AssistantConversationService.observationState(history(GUESS,601,"NEEDS_INPUT"),GUESS,true).reason());
        assertEquals("TARGET_INELIGIBLE",AssistantConversationService.observationState(history(GUESS,20,"NEEDS_INPUT"),GUESS,false).reason());
    }
    @Test void evidenceIsDefaultOffAndWriteFailureDoesNotEscape()throws Exception{
        String id=UUID.randomUUID().toString();
        assertEquals("DISABLED",ObservationBranchEvidence.write("",id,"VALID","OBJECT","WORKFLOW","ORIGINAL",false,false,false,"NEEDS_INPUT"));
        Path file=directory.resolve("not-directory");Files.writeString(file,"sentinel");
        assertEquals("UNVERIFIED",ObservationBranchEvidence.write(file.toString(),id,"VALID","OBJECT","WORKFLOW","ORIGINAL",false,false,false,"NEEDS_INPUT"));
    }
    @Test void whitelistRejectsArbitraryValuesAndNeverOverwrites()throws Exception{
        String id=UUID.randomUUID().toString();
        assertEquals("WRITTEN",ObservationBranchEvidence.write(directory.toString(),id,"SECRET_MARKER","SECRET_MARKER","SECRET_MARKER","SECRET_MARKER",false,false,false,"SECRET_MARKER"));
        String text=Files.readString(directory.resolve(id+".json"));assertFalse(text.contains("SECRET_MARKER"));assertTrue(text.contains("OTHER"));
        assertEquals("UNVERIFIED",ObservationBranchEvidence.write(directory.toString(),id,"VALID","OBJECT","WORKFLOW","ORIGINAL",true,true,true,"NEEDS_INPUT"));
        assertEquals(text,Files.readString(directory.resolve(id+".json")));
    }
    @Test void outputMetadataContainsOnlyShapeAndKnownKind(){
        assertEquals(List.of("OBJECT","OTHER"),ObservationBranchEvidence.classify("{\"kind\":\"SECRET_MARKER\"}"));
        assertEquals(List.of("MALFORMED","OTHER"),ObservationBranchEvidence.classify("SECRET_MARKER"));
        assertEquals(List.of("NON_OBJECT","OTHER"),ObservationBranchEvidence.classify("[]"));
    }
}

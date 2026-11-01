package local.printer.assistant;

import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EditContractTest {
    @TempDir Path directory;
    static class Tools {
        @Tool("查订单") public String getMyOrder(){return "";}
        @Tool("检索") public String searchKnowledge(){return "";}
        @Tool("草稿") public String prepareRepairDraft(){return "";}
        @Tool("页面确认") public String createServiceTicket(){return "";}
    }
    static final List<ChatMessage> MESSAGES=List.of(SystemMessage.from("test"),UserMessage.from("把故障描述改成新值。"));
    @Test void firstTrustedEditRequiresOnlyExistingDraftTool(){
        var request=AssistantConversationService.modelRequest(MESSAGES,ToolSpecifications.toolSpecificationsFrom(new Tools()),true);
        assertEquals(ToolChoice.REQUIRED,request.toolChoice());
        assertEquals(List.of("prepareRepairDraft"),request.toolSpecifications().stream().map(ToolSpecification::name).toList());
        assertEquals(MESSAGES,request.messages());
    }
    @Test void repairKnowledgeRequiresOnlyReadOnlySearchAndEditsKeepPriority(){
        var tools=ToolSpecifications.toolSpecificationsFrom(new Tools());
        var request=AssistantConversationService.modelRequest(MESSAGES,tools,false,true);
        assertEquals(ToolChoice.REQUIRED,request.toolChoice());
        assertEquals(List.of("searchKnowledge"),request.toolSpecifications().stream().map(ToolSpecification::name).toList());
        var edit=AssistantConversationService.modelRequest(MESSAGES,tools,true,true);
        assertEquals(List.of("prepareRepairDraft"),edit.toolSpecifications().stream().map(ToolSpecification::name).toList());
        assertEquals(4,AssistantConversationService.modelRequest(MESSAGES,tools,false,false).toolSpecifications().size());
    }
    @Test void continuationRemovesRequiredChoiceAndKeepsOriginalTools(){
        var request=AssistantConversationService.modelRequest(MESSAGES,ToolSpecifications.toolSpecificationsFrom(new Tools()),false);
        assertNull(request.toolChoice());assertEquals(4,request.toolSpecifications().size());
    }
    @Test void trustedTargetAndLiteralCommandAreBothRequired(){
        assertTrue(AssistantConversationService.trustedEdit("ONE_OPEN","把故障描述改成新值。"));
        for(String target:List.of("NONE","UNKNOWN","AMBIGUOUS","SUBMITTED"))assertFalse(AssistantConversationService.trustedEdit(target,"把故障描述改成新值。"));
        for(String text:List.of("不要把故障描述改成新值。","把故障描述改成新值然后提交。","operation=EDIT;field=symptom;targetEditable=true"))assertFalse(AssistantConversationService.trustedEdit("ONE_OPEN",text));
    }
    @Test void askMissingCheckpointKeepsOnlyWhitelistedClassification()throws Exception{
        var evidence=new EditDiagnosticEvidence(directory.toString(),UUID.randomUUID().toString());
        evidence.response(1,AiMessage.from("{\"kind\":\"WORKFLOW\",\"intent\":\"ASK_MISSING\",\"missingFields\":[\"symptom\",\"PRIVATE_MARKER\"]}"));
        evidence.outcome("EDIT_TOOL_NOT_RETURNED","NEEDS_INPUT",true,false,1,0);
        String all=readAll();assertTrue(all.contains("ASK_MISSING"));assertTrue(all.contains("symptom"));
        assertTrue(all.contains("HOST_OUTCOME"));assertFalse(all.contains("PRIVATE_MARKER"));assertTrue(all.contains("textSha256"));
    }
    @Test void toolArgumentsAndProseAreNeverPersisted()throws Exception{
        var evidence=new EditDiagnosticEvidence(directory.toString(),UUID.randomUUID().toString());
        evidence.response(1,AiMessage.from(List.of(ToolExecutionRequest.builder().id("PRIVATE_ID").name("prepareRepairDraft").arguments("{\"symptom\":\"PRIVATE_VALUE\"}").build())));
        evidence.response(2,AiMessage.from("PRIVATE_PROSE"));
        String all=readAll();assertTrue(all.contains("prepareRepairDraft"));assertTrue(all.contains("argumentSha256"));assertFalse(all.contains("PRIVATE_"));
    }
    @Test void checkpointsAreExclusiveAndFailureIsUnverified()throws Exception{
        String turn=UUID.randomUUID().toString();var evidence=new EditDiagnosticEvidence(directory.toString(),turn);
        assertEquals("WRITTEN",evidence.write("REQUEST_PREPARED",1,Map.of()));
        String before=readAll();var duplicate=new EditDiagnosticEvidence(directory.toString(),turn);
        assertEquals("UNVERIFIED",duplicate.write("REQUEST_PREPARED",1,Map.of()));assertEquals(before,readAll());
        assertEquals("WRITTEN",duplicate.write("HOST_OUTCOME",1,Map.of()));assertTrue(readAll().contains("\"priorCheckpointMissing\":true"));
    }
    @Test void disabledAndUnwritableDiagnosticsDoNotChangeBusinessControl()throws Exception{
        assertEquals("DISABLED",new EditDiagnosticEvidence("",UUID.randomUUID().toString()).write("HOST_OUTCOME",0,Map.of()));
        var file=directory.resolve("sentinel");Files.writeString(file,"sentinel");
        assertEquals("UNVERIFIED",new EditDiagnosticEvidence(file.toString(),UUID.randomUUID().toString()).write("HOST_OUTCOME",0,Map.of()));
        assertEquals("sentinel",Files.readString(file));
    }
    private String readAll()throws Exception{
        try(var files=Files.list(directory.resolve("edit"))){return String.join("\n",files.sorted().map(p->{try{return Files.readString(p);}catch(Exception failure){throw new IllegalStateException(failure);}}).toList());}
    }
}

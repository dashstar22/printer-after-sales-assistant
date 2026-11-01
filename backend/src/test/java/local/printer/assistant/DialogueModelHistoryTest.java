package local.printer.assistant;

import dev.langchain4j.data.message.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class DialogueModelHistoryTest {
    ConversationMapper.Message message(String turn,String role,String content,String response){
        return new ConversationMapper.Message(1,turn,role,content,response,LocalDateTime.now());
    }
    @Test void historicalReceiptsDoNotDiscloseOldBodiesFieldsOrInstructions(){
        var history=List.of(message("old","USER","报修字段问题",null),
            message("old","ASSISTANT","旧资料正文和内部提示", "{\"status\":\"ANSWERED\",\"draft\":{\"contact\":\"旧联系人\"}}"),
            message("now","USER","现在卡纸",null));
        var messages=AssistantConversationService.modelHistory(history);assertEquals(3,messages.size());
        assertInstanceOf(UserMessage.class,messages.get(0));assertInstanceOf(AiMessage.class,messages.get(1));
        var receipt=JsonMapper.builder().build().readTree(((AiMessage)messages.get(1)).text());
        assertEquals("old",receipt.path("completedTurn").asString());assertEquals("ANSWERED",receipt.path("status").asString());assertEquals(2,receipt.size());
        assertEquals("现在卡纸",((UserMessage)messages.get(2)).singleText());
    }
    @Test void truncatedOrUnmatchedHistoricalTurnsDoNotBecomeOutstandingRequests(){
        var messages=AssistantConversationService.modelHistory(List.of(
            message("cut","ASSISTANT","截断的历史结果","{}"),message("missing","USER","没有匹配结果的旧输入",null),
            message("different","ASSISTANT","其他轮次结果","{}"),message("now","USER","当前问题",null)));
        assertEquals(1,messages.size());assertEquals("当前问题",((UserMessage)messages.get(0)).singleText());
    }
    @Test void absentLegacyResponseClosesItsTurnWithoutInferringSuccess(){
        var messages=AssistantConversationService.modelHistory(List.of(message("old","USER","旧问题",null),
            message("old","ASSISTANT","旧显示文本",null),message("now","USER","当前问题",null)));
        assertTrue(((AiMessage)messages.get(1)).text().contains("RESULT_UNKNOWN"));
    }
}

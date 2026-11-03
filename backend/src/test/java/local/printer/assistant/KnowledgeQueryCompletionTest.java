package local.printer.assistant;

import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeQueryCompletionTest {
    List<ConversationMapper.Message> history(String previous,String current){
        var now=LocalDateTime.now();return List.of(new ConversationMapper.Message(1,"a","USER",previous,null,now.minusMinutes(1)),
            new ConversationMapper.Message(2,"a","ASSISTANT","资料", "{\"status\":\"ANSWERED\",\"citations\":[{}]}",now.minusMinutes(1)),
            new ConversationMapper.Message(3,"b","USER",current,null,now));
    }
    @Test void directAndMixedInputsAreUntouchedWithoutAdditionalModelCalls(){
        for(String text:List.of("喷头怎么清理","你好，喷头怎么清理","把联系人改成小王","切换到其他设备怎么操作")){
            var q=KnowledgeQueryCompletion.resolve(text,history("喷头怎么清理",text));assertNull(q.query());assertFalse(q.clarification());assertEquals(0,q.rewriteCalls());
        }
    }
    @Test void clearReferenceUsesOnlyImmediateOwnedConversationQuestion(){
        var q=KnowledgeQueryCompletion.resolve("那具体怎么操作",history("喷头怎么清理","那具体怎么操作"));
        assertEquals("喷头怎么清理；那具体怎么操作",q.query());assertEquals("PREVIOUS_KNOWLEDGE_TURN",q.reason());assertEquals(0,q.rewriteCalls());
    }
    @Test void exactCapacityQuestionCanBeCompletedButAnIdentifierStillCannot(){
        String prior="设备文件恰好8388608字节，容量按压缩后判断吗？";
        var q=KnowledgeQueryCompletion.resolve("那怎么操作",history(prior,"那怎么操作"));
        assertEquals(prior+"；那怎么操作",q.query());assertEquals(0,q.rewriteCalls());
        assertTrue(KnowledgeQueryCompletion.resolve("那怎么操作",history("账号为8388608 MiB，设备怎么处理？","那怎么操作")).clarification());
    }
    @Test void ambiguityPrivateFieldsSwitchingAndExpiredContextClarify(){
        for(String prior:List.of("喷头怎么清理或者卡纸怎么办","电话13800138000，喷头怎么清理","另一台的喷头怎么清理","联系人小王，喷头怎么清理"))
            assertTrue(KnowledgeQueryCompletion.resolve("那怎么操作",history(prior,"那怎么操作")).clarification());
        assertTrue(KnowledgeQueryCompletion.resolve("那怎么操作",List.of()).clarification());
        var h=new ArrayList<>(history("喷头怎么清理","那怎么操作"));var old=h.get(1);
        h.set(1,new ConversationMapper.Message(old.id(),old.turnId(),old.role(),old.content(),old.responseJson(),old.createdAt().minusHours(1)));
        assertTrue(KnowledgeQueryCompletion.resolve("那怎么操作",h).clarification());
    }
}

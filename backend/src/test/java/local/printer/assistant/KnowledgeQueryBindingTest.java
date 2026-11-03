package local.printer.assistant;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import local.printer.knowledge.KnowledgeRetrievalService;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

class KnowledgeQueryBindingTest {
    @ParameterizedTest @ValueSource(strings={"设备日志恰好2097152字节能保存吗？","压缩文件8388608 bytes是否超出设备容量？",
        "设备的文件有12345678 KiB，容量按压缩后判断吗？","我看到2097152字节，打印机能保存吗？","设备文件大小16777216字节和33554432字节，分别可以吗？"})
    void capacityMeasurementsKeepTheirExactValueAndAreNotMistakenForIdentity(String text){
        assertTrue(KnowledgeQueryBinding.safe(text));assertEquals(text,KnowledgeQueryBinding.select(text,"改写查询"));
    }
    @ParameterizedTest @ValueSource(strings={"电话是2097152，设备如何重启？","电话是2097152字节，设备如何重启？",
        "账号 2097152 MiB，设备如何重启？","序列号：2097152字节，设备如何重启？","设备ID为A2097152字节，能保存吗？",
        "我的手机号13800138000，设备文件2097152字节能保存吗？","设备文件2097152能保存吗？","设备标识ABC2097152字节，如何保存？",
        "设备文件2097152 bytecode，能保存吗？","设备文件2097152字节，另一个编号1234567",
        "{\"电话\":\"2097152字节\"}，设备如何重启？","设备联系电话（2097152字节），怎么重启？"})
    void unitExceptionDoesNotAdmitContactsIdentifiersOrBareLongNumbers(String text){assertFalse(KnowledgeQueryBinding.safe(text));}

    @Test void explicitDeviceQuestionBindsFullCurrentTextEvenIfModelParaphrasesOrChangesPunctuation(){
        String current="我的设备显示 Z91，应该等待多久才能打开外盖？";
        assertTrue(ServiceDialoguePolicy.requiresKnowledge(current));
        for(String proposed:List.of("Z91打开外盖等待时间","我的设备显示Z91应该等待多久才能打开外盖","别的型号有多久保修","Z91"))
            assertEquals(current,KnowledgeQueryBinding.select(current,proposed));
        assertEquals(current,KnowledgeQueryBinding.select(current,null));
    }

    @Test void noPrivateCurrentMessageIsAutomaticallyForwardedAndOnlyLiteralSafeQuestionCanBeSelected(){
        String current="我的电话：13800138000；打印机如何重启？";
        assertNull(KnowledgeQueryBinding.select(current,current));
        assertNull(KnowledgeQueryBinding.select(current,"历史设备的重启步骤"));
        assertEquals("打印机如何重启？",KnowledgeQueryBinding.select(current,"打印机如何重启？"));
        for(String input:List.of("我的邮箱是 private@example.test，设备如何重启？","联系人：私人姓名，设备如何重启？","订单号：1234567，设备如何重启？"))
            assertNull(KnowledgeQueryBinding.select(input,input));
    }

    @Test void plainSymptomAndOtherDialogueRetainCurrentSubstringContract(){
        assertEquals("反复卡纸",KnowledgeQueryBinding.select("我的设备反复卡纸","反复卡纸"));
        assertNull(KnowledgeQueryBinding.select("我的设备反复卡纸","上一轮已经回答的保修政策"));
        assertNull(KnowledgeQueryBinding.select("继续填写报修",null));
        assertFalse(KnowledgeQueryBinding.safe("a".repeat(1001)));
        assertFalse(KnowledgeQueryBinding.safe("打印机怎么重启\u0000"));
        assertTrue(KnowledgeQueryBinding.safe("联系人最多能填多少字？"));
        assertTrue(KnowledgeQueryBinding.safe("联系人是否必须填写？"));
    }

    @Test void actualToolSendsOnlyBoundUserQuestionAndDoesNotForwardModelGeneratedQuery()throws Exception{
        var retrieval=mock(KnowledgeRetrievalService.class);
        when(retrieval.searchOwnedForAssistant(anyLong(),anyLong(),anyString())).thenReturn(
            new KnowledgeRetrievalService.Result("NO_EVIDENCE","FIXTURE",0,0,0,false,0,List.of(),java.util.Map.of()));
        var execution=new AssistantExecution(new MockEnvironment());
        try {
            var service=new AssistantConversationService(null,null,null,null,retrieval,null,mock(PlatformTransactionManager.class),null,execution,null);
            var target=Class.forName(AssistantConversationService.class.getName()+"$Target").getDeclaredConstructors()[0];target.setAccessible(true);
            var constructor=AssistantConversationService.BoundTools.class.getDeclaredConstructors()[0];constructor.setAccessible(true);
            String current="我的设备显示 Z91，应该等待多久才能打开外盖？";
            var bound=(AssistantConversationService.BoundTools)constructor.newInstance(service,1L,2L,"owned","turn",List.of(),execution.begin(),target.newInstance("NONE",null),current);
            assertTrue(bound.searchKnowledge("任意模型编造的跨型号检索词").contains("NO_EVIDENCE"));
            verify(retrieval).searchOwnedForAssistant(1L,2L,current);verifyNoMoreInteractions(retrieval);
        }finally{execution.close();}
    }
}

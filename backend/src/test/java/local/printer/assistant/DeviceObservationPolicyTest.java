package local.printer.assistant;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DeviceObservationPolicyTest {
    @ParameterizedTest @ValueSource(strings={"这台设备昨天实际完成了多少个打印作业？请直接报出计数。",
        "我的打印机今天打印了几页？","当前这台机器的累计计数是多少？","告诉我这台设备现在完成了多少个任务。"})
    void runtimeReadingsHaveAnExplicitLocalMissingInputRoute(String text){
        assertTrue(DeviceObservationPolicy.needsObservedCount(text));assertFalse(ServiceDialoguePolicy.requiresKnowledge(text));
        var decision=new DialogueRouterTest().route(text,null);
        assertEquals("DEVICE_OBSERVATION",decision.intent());assertEquals("DEVICE_READING_UNAVAILABLE",decision.reason());
        assertEquals("RULE",decision.layer());assertEquals("NEEDS_INPUT",decision.answer().status());
        assertTrue(decision.answer().text().contains("没有接入"));assertTrue(decision.answer().text().contains("对应时间范围"));
        assertTrue(decision.answer().citations().isEmpty());assertNull(decision.topic());assertFalse(decision.prepare());assertNull(decision.state());
    }
    @ParameterizedTest @ValueSource(strings={"设备当前计数如何查看？","如何查看打印机昨天的计数？","设备的作业记录保留多久？",
        "设备现在最大作业数是多少？","这台设备累计计数的单位和上限是多少？","我看到2097152字节，设备能保存吗？",
        "我看到设备昨天打印50页，今天实际完成多少个作业？","这台设备昨天实际完成多少个作业？然后请准备报修。",
        "把现场补充信息改成设备昨天实际完成多少个作业？","设备昨天实际完成多少个作业？另外如何连接网络？",
        "设备计数显示0页，代表什么？","电话是2097152，设备昨天完成多少个作业？","设备昨天的计数可以清零吗？"})
    void rulesSuppliedValuesAndMixedWorkflowRemainOutsideTheNarrowReadingRule(String text){
        assertFalse(DeviceObservationPolicy.needsObservedCount(text));
    }
    @Test void readingQuestionPausesCollectionWithoutWritingFieldsOrRefreshingAuthority(){
        var router=new DialogueRouterTest();var state=router.route("反复卡纸，请准备报修",null).state();
        var result=router.route("设备昨天实际完成多少个打印作业？",state);
        assertEquals("PAUSED",result.state().phase());assertEquals(state.values(),result.state().values());
        assertEquals(state.expiresAt(),result.state().expiresAt());assertEquals(state.authorizedTurn(),result.state().authorizedTurn());
        assertFalse(result.prepare());assertEquals(List.of(),result.answer().citations());
    }
}

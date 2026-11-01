package local.printer.assistant;

import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DialogueRouterTest {
    static final LocalDateTime NOW=LocalDateTime.of(2026,11,1,12,0);
    DialogueRouter.Decision route(String text,DialogueState state,LocalDateTime now){return DialogueRouter.route("c",1,2,"t",text,state,"NONE",now);}
    DialogueRouter.Decision route(String text,DialogueState state){return route(text,state,NOW);}
    @ParameterizedTest @ValueSource(strings={"你好","你好！","谢谢！","你能做什么","查看工单"})
    void completeExpressionsAreRules(String text){var d=route(text,null);assertEquals("RULE",d.layer());assertNotNull(d.answer());assertFalse(d.prepare());}
    @ParameterizedTest @ValueSource(strings={"你好，报修要准备什么","联系人和联系方式最多可以填写多少字？","联系方式一定要填手机号吗","报修字段报错怎么办"})
    void businessSurvivesGreetingAndResolvesActualTopic(String text){var d=route(text,null);assertEquals("KNOWLEDGE",d.intent());assertNotNull(d.topic());assertNull(d.answer());}
    @ParameterizedTest @ValueSource(strings={"咋回事","卡住了","小王","你好，设备坏了","谢谢，把联系人改成小王"})
    void unknownShortMessagesStillReachNaturalLanguageHandling(String text){assertEquals("MODEL",route(text,null).layer());}
    @Test void collectsSingleFieldsAndKeepsPunctuationAndLiteralValues(){
        var a=route("反复卡纸，请准备报修。",null);assertEquals("extra",a.state().pendingField());assertEquals("反复卡纸",a.state().symptom());
        var b=route("进纸处卡住，屏幕没有错误码。已取出纸张。",a.state());assertEquals("contact",b.state().pendingField());assertEquals("进纸处卡住，屏幕没有错误码。已取出纸张。",b.state().extra());
        var c=route("小王",b.state());assertEquals("小王",c.state().contact());assertEquals("contactMethod",c.state().pendingField());
        var d=route("13800000000",c.state());assertTrue(d.prepare());assertTrue(d.state().missing().isEmpty());assertEquals(a.state().expiresAt(),d.state().expiresAt());
    }
    @Test void collectsLabeledMultipleFields(){
        var d=route("故障描述：出纸卡住。；现场补充信息：屏幕正常，已经检查纸盘。；联系人：小王；联系方式：电话13800000000；请准备报修",null);
        assertTrue(d.prepare());assertEquals("小王",d.state().contact());
    }
    @Test void unicodeFieldWhitespaceUsesExistingDraftContract(){
        var d=route("故障：\u3000卡纸😀\u00a0；现场：\u00a0屏幕正常，已检查。\u3000；联系人：\u00a0小王\u00a0；联系方式：\u3000123\u3000；请准备报修",null);
        assertTrue(d.prepare());assertEquals(List.of("卡纸😀","屏幕正常，已检查。","小王","123"),d.state().values());
    }
    @ParameterizedTest @ValueSource(strings={"你帮我猜","不要填了","小王，然后提交","联系人：你帮我猜","联系人：小王；联系人：小李","这个晚点再说"})
    void ambiguityDoesNotBecomeField(String text){
        var initial=route("反复卡纸，请准备报修",null).state();var d=route(text,initial);
        assertNull(d.state().extra());assertFalse(d.prepare());assertNotEquals("MODEL",d.layer());
    }
    @Test void questionPausesAndExplicitResumeKeepsFieldsAndOriginalExpiry(){
        var initial=route("反复卡纸，请准备报修",null).state();
        var q=route("联系方式一定要填手机号吗",initial);assertEquals("PAUSED",q.state().phase());assertNotNull(q.topic());
        var blocked=route("小王",q.state());assertEquals("PAUSED_REQUIRES_RESUME",blocked.reason());assertNull(blocked.state().contact());
        var resumed=route("继续填写报修",q.state(),NOW.plusMinutes(9));assertEquals("COLLECTING",resumed.state().phase());
        assertEquals(initial.expiresAt(),resumed.state().expiresAt());assertEquals(initial.symptom(),resumed.state().symptom());
        var expired=route("小王",resumed.state(),NOW.plusMinutes(10));assertEquals("EXPIRED",expired.state().phase());assertFalse(expired.prepare());
    }
    @ParameterizedTest @ValueSource(strings={"不是我要报修","他说我要报修","他说，申请报修","反复卡纸，暂时不申请，请准备报修","小王，现在保存，请准备报修","“请准备报修”"})
    void quotedIndirectAndNegativeStartsNeverEstablishState(String text){var d=route(text,null);assertNull(d.state());assertFalse(d.prepare());}
    @Test void cancelAndHistoryNeverCreateAuthorization(){
        var initial=route("反复卡纸，请准备报修",null).state();var cancelled=route("取消填写报修",initial);
        assertEquals("CANCELLED",cancelled.state().phase());assertEquals("MODEL",route("小王",cancelled.state()).layer());
        assertNull(route("现场补充信息：屏幕正常；联系人：小王；联系方式：123",null).state());
    }
}

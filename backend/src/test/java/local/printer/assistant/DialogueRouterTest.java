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
    private DialogueRouter.Decision turn(String turn,String text,DialogueState state,LocalDateTime now){return DialogueRouter.route("c",1,2,turn,text,state,"NONE",now);}
    private void unchangedClarification(DialogueState state,DialogueRouter.Decision decision){
        assertEquals(state,decision.state());assertEquals("STATE",decision.layer());assertNotNull(decision.answer());
        assertEquals("NEEDS_INPUT",decision.answer().status());assertNull(decision.topic());assertFalse(decision.prepare());assertFalse(decision.semantic());
    }
    @ParameterizedTest @ValueSource(strings={"打印时没有什么异常声音","字段校验错误","字段 INVALID_ARGUMENT","打印机卡纸该怎么处理","那具体怎么操作",
        "不是在问报修要准备什么","“联系方式一定要填写手机号吗”","如果报修要准备什么"})
    void uncertainCollectionInputClarifiesWithoutPausingFillingOrRenewing(String text){
        var state=route("反复卡纸，请准备报修",null).state();
        unchangedClarification(state,turn("question",text,state,NOW.plusMinutes(9)));
    }
    @ParameterizedTest @ValueSource(strings={"字段校验错误","字段 INVALID_ARGUMENT","联系方式格式"})
    void coarseTopicOutsideCollectionFallsBackWithoutAuthorization(String text){
        var decision=route(text,null);assertEquals("MODEL",decision.layer());assertNull(decision.state());assertNull(decision.topic());assertFalse(decision.prepare());
        var cancelled=route("取消填写报修",route("请准备报修",null).state()).state();
        var afterCancel=route(text,cancelled);assertEquals("MODEL",afterCancel.layer());assertEquals(cancelled,afterCancel.state());assertNull(afterCancel.topic());
    }
    @Test void explicitLabelTakesPrecedenceOverCoarseKnowledgeSignal(){
        var state=route("反复卡纸，请准备报修",null).state();
        var decision=turn("extra","现场补充信息：屏幕显示字段校验错误",state,NOW.plusMinutes(9));
        assertEquals("屏幕显示字段校验错误",decision.state().extra());assertEquals("COLLECTING",decision.state().phase());
        assertEquals("contact",decision.state().pendingField());assertEquals("STATE",decision.layer());assertNull(decision.topic());assertFalse(decision.prepare());
        assertEquals(state.authorizedTurn(),decision.state().authorizedTurn());assertEquals(state.expiresAt(),decision.state().expiresAt());
    }
    @ParameterizedTest @ValueSource(strings={"现场：屏幕正常；联系人：小王，然后提交","现场：屏幕正常；立刻建单",
        "联系人：小王；联系人：小李","现场：屏幕正常；现场补充信息：已重启","现场：屏幕正常；；联系人：小王",
        "现场：屏幕正常； \u00a0；联系人：小王","现场：屏幕正常\n\n联系人：小王","现场：屏幕正常\n已重启",
        "屏幕正常；联系人：小王","；现场：屏幕正常","现场：屏幕正常；联系人：","现场：屏幕正常；备注：已重启",
        "现场：屏幕正常；联系人：联系方式一定要填手机号吗"})
    void recognizedInvalidLabelsRejectTheWholeMessage(String text){
        var state=route("反复卡纸，请准备报修",null).state();unchangedClarification(state,route(text,state));
    }
    @ParameterizedTest @ValueSource(strings={"联系人：小王；联系人：小李，请准备报修","现场：屏幕正常；立刻建单；请准备报修",
        "故障：卡纸；故障描述：断纸；请准备草稿","现场：屏幕正常；；联系人：小王；我要报修","现场：屏幕正常\n已重启，申请报修",
        "现场：屏幕正常；联系人：联系方式一定要填手机号吗；请准备报修"})
    void invalidInitialLabelsNeverFallBackToSymptomOrTopic(String text){
        var decision=route(text,null);unchangedClarification(null,decision);assertEquals("INVALID_LABELED_FIELDS",decision.reason());
    }
    @ParameterizedTest @ValueSource(strings={"现场：屏幕正常，已检查。；联系人：小王；","现场：屏幕正常，已检查。;联系人：小王; \u00a0",
        "现场：屏幕正常，已检查。\n联系人：小王\n","现场：屏幕正常，已检查。\r\n联系人：小王\r\n",
        "\u00a0现场：\u3000屏幕正常，已检查。\u00a0；\u3000联系人：\u00a0小王\u3000；\u00a0"})
    void validSegmentsPreserveFieldPunctuationAndExistingWhitespaceContract(String text){
        var state=route("反复卡纸，请准备报修",null).state();var decision=route(text,state);
        assertEquals("屏幕正常，已检查。",decision.state().extra());assertEquals("小王",decision.state().contact());
        assertEquals("contactMethod",decision.state().pendingField());assertNull(decision.topic());assertFalse(decision.prepare());
    }
    @ParameterizedTest @ValueSource(strings={"无","没有","不提供"})
    void labeledAndUnlabeledContactRefusalsUseTheSameChecks(String value){
        var contact=route("现场：屏幕正常",route("反复卡纸，请准备报修",null).state()).state();
        unchangedClarification(contact,route(value,contact));unchangedClarification(contact,route("联系人："+value,contact));
        var method=route("小王",contact).state();
        unchangedClarification(method,route(value,method));unchangedClarification(method,route("联系方式："+value,method));
        unchangedClarification(null,route("联系人："+value+"；请准备报修",null));
        unchangedClarification(null,route("联系方式："+value+"；请准备报修",null));
    }
    @ParameterizedTest @ValueSource(strings={"123","模拟站内联系","请通过站内联系"})
    void contactMethodRemainsFreeTextWithinExistingConservativeChecks(String value){
        var state=route("故障：卡纸；现场：屏幕正常；联系人：小王；请准备报修",null).state();
        // The existing safety veto still rejects commands; no new phone-number contract is introduced.
        if(value.startsWith("请")){unchangedClarification(state,route(value,state));unchangedClarification(state,route("联系方式："+value,state));return;}
        var literal=route(value,state);var labeled=route("联系方式："+value,state);
        assertTrue(literal.prepare());assertTrue(labeled.prepare());assertEquals(value,literal.state().contactMethod());assertEquals(value,labeled.state().contactMethod());
    }
    @ParameterizedTest @ValueSource(strings={"请准备草稿","请准备报修","我要报修","申请报修","帮我报修"})
    void ninthMinuteBareRepeatPreservesEveryStateFieldAndDoesNotPrepare(String text){
        var state=turn("authorized","反复卡纸，请准备报修",null,NOW).state();
        unchangedClarification(state,turn("repeat",text,state,NOW.plusMinutes(9)));
    }
    @Test void repeatAfterFieldUpdateKeepsPendingAndLastTurn(){
        var initial=turn("authorized","反复卡纸，请准备报修",null,NOW).state();
        var state=turn("extra","屏幕正常",initial,NOW.plusMinutes(1)).state();
        unchangedClarification(state,turn("repeat","请准备报修",state,NOW.plusMinutes(9)));
    }
    @ParameterizedTest @ValueSource(strings={"出纸卡住，请准备报修","现场：屏幕正常；请准备报修",
        "故障：新故障；现场：已检查；联系人：小李；联系方式：123；请准备报修"})
    void repeatedPreparationWithContentNeitherRestartsNorMerges(String text){
        var state=turn("authorized","反复卡纸，请准备报修",null,NOW).state();
        unchangedClarification(state,turn("repeat",text,state,NOW.plusMinutes(9)));
        var paused=turn("pause","暂停填写",state,NOW.plusMinutes(1)).state();
        unchangedClarification(paused,turn("repeat-paused",text,paused,NOW.plusMinutes(9)));
    }
    @Test void pausedFieldsAndPreparationRequireResumeButReadonlyConsultationRemains(){
        var initial=turn("authorized","反复卡纸，请准备报修",null,NOW).state();var paused=turn("pause","暂停填写",initial,NOW.plusMinutes(1)).state();
        for(String input:List.of("现场：屏幕正常","小王","请准备报修"))unchangedClarification(paused,turn("paused-input",input,paused,NOW.plusMinutes(9)));
        var consult=turn("consult","打印机卡纸该怎么处理",paused,NOW.plusMinutes(9));
        assertEquals("MODEL",consult.layer());assertEquals("KNOWLEDGE",consult.intent());assertEquals(paused,consult.state());assertFalse(consult.prepare());
        var rule=turn("rule","联系方式一定要填手机号吗",paused,NOW.plusMinutes(9));
        assertEquals("RULE",rule.layer());assertNotNull(rule.topic());assertEquals("PAUSED",rule.state().phase());assertEquals(paused.values(),rule.state().values());assertFalse(rule.prepare());
    }
    @Test void failedCompleteCollectionDoesNotRetryOnBarePreparationButExplicitCorrectionStillPrepares(){
        var state=turn("authorized","故障：卡纸；现场：屏幕正常；联系人：小王；联系方式：123；请准备报修",null,NOW).state();
        assertTrue(state.missing().isEmpty());assertEquals("COLLECTING",state.phase());
        unchangedClarification(state,turn("repeat","请准备报修",state,NOW.plusMinutes(9)));
        var correction=turn("correct","联系人：小李",state,NOW.plusMinutes(9));
        assertTrue(correction.prepare());assertEquals("小李",correction.state().contact());
        assertEquals(state.authorizedTurn(),correction.state().authorizedTurn());assertEquals(state.expiresAt(),correction.state().expiresAt());
    }
    @Test void exactExpiryBlocksFieldsButExplicitPreparationEstablishesFreshAuthority(){
        var state=turn("authorized","反复卡纸，请准备报修",null,NOW).state();
        var expired=turn("late","现场：屏幕正常",state,state.expiresAt());
        assertEquals("EXPIRED",expired.state().phase());assertEquals(state.values(),expired.state().values());assertFalse(expired.prepare());
        var restarted=turn("new-start","请准备报修",state,state.expiresAt());
        assertEquals("COLLECTING",restarted.state().phase());assertEquals(Arrays.asList(null,null,null,null),restarted.state().values());
        assertEquals("new-start",restarted.state().authorizedTurn());assertEquals(state.expiresAt().plusMinutes(10),restarted.state().expiresAt());assertFalse(restarted.prepare());
    }
    @Test void cancelledCollectionCanBeExplicitlyRestartedWithoutOldFields(){
        var state=route("取消填写报修",route("反复卡纸，请准备报修",null).state()).state();
        var restarted=turn("restart","请准备报修",state,NOW.plusMinutes(1));
        assertEquals("COLLECTING",restarted.state().phase());assertEquals(Arrays.asList(null,null,null,null),restarted.state().values());assertEquals("restart",restarted.state().authorizedTurn());
    }
    @ParameterizedTest @ValueSource(strings={"ONE_OPEN","SUBMITTED","UNKNOWN"})
    void existingDraftAndUncertainTargetsCannotCollectOrReauthorize(String target){
        var state=route("反复卡纸，请准备报修",null).state();
        for(String text:List.of("现场：屏幕显示字段校验错误","现场：屏幕正常","请准备报修","继续填写报修")){
            var decision=DialogueRouter.route("c",1,2,"blocked",text,state,target,NOW.plusMinutes(9));
            assertEquals(state,decision.state());assertFalse(decision.prepare());assertNull(decision.topic());
        }
    }
    private DialogueRouter.Decision semantic(String text,DialogueState state){
        return DialogueRouter.route("c",1,2,"semantic-turn",text,state,"NONE",NOW.plusMinutes(9),true);
    }
    private void semanticProposal(DialogueState state,DialogueRouter.Decision decision){
        assertTrue(decision.semantic());assertEquals("MODEL",decision.layer());assertEquals("COLLECT_FIELDS",decision.intent());
        assertEquals("OBSERVATION_SEMANTIC_PROPOSAL",decision.reason());assertEquals(state,decision.state());
        assertNull(decision.answer());assertNull(decision.topic());assertFalse(decision.prepare());
    }
    @Test void observationSemanticsRemainDisabledForTheOriginalRouteContract(){
        var state=route("反复卡纸，请准备报修",null).state();
        for(String text:List.of("屏幕正常","打印时没有什么异常声音","打印机卡纸该怎么处理")){
            var original=turn("legacy",text,state,NOW.plusMinutes(9));
            var disabled=DialogueRouter.route("c",1,2,"legacy",text,state,"NONE",NOW.plusMinutes(9),false);
            assertEquals(original,disabled);assertFalse(original.semantic());
        }
        assertEquals("屏幕正常",route("屏幕正常",state).state().extra());
        unchangedClarification(state,route("打印时没有什么异常声音",state));
        var legacyDecision=new DialogueRouter.Decision("STATE","CLARIFY","LEGACY",state,null,false,null);
        assertFalse(legacyDecision.semantic());
    }
    @ParameterizedTest @ValueSource(strings={"屏幕正常，已检查。","打印时没有什么异常声音","打印机卡纸该怎么处理","那具体怎么操作","苹果"})
    void allEligibleUnlabeledExtraContinuationsBecomeProposalsWithoutFillingOrPausing(String text){
        var state=turn("authorized","反复卡纸，请准备报修",null,NOW).state();semanticProposal(state,semantic(text,state));
    }
    @ParameterizedTest @ValueSource(strings={"字段校验错误","字段 INVALID_ARGUMENT","联系方式格式","屏幕显示字段校验错误"})
    void coarseRuleTopicsStillClarifyBeforeObservationSemantics(String text){
        var state=route("反复卡纸，请准备报修",null).state();unchangedClarification(state,semantic(text,state));
    }
    @ParameterizedTest @ValueSource(strings={"不","否","不用","没想好","算了","你帮我猜","随便","不知道错误码，但已重启","不清楚有没有错误码","不确定",
        "屏幕提示“请更换墨盒”","他说屏幕正常","她说屏幕正常","引用屏幕提示","示例屏幕正常","如果屏幕正常","假如屏幕正常",
        "屏幕正常，然后提交","屏幕正常，同时提交","不要保存","不想保存","别填","不用填","不填","不申请","把联系人改成小王","将联系人改为小王",
        "保存","修改","提交","确认","删除","清空","帮我猜","推测","编造","代填","备注：屏幕正常"})
    void existingControlUncertaintyAndColonGuardsCannotBeBypassedBySemanticCandidates(String text){
        var state=route("反复卡纸，请准备报修",null).state();var decision=semantic(text,state);
        assertFalse(decision.semantic());assertEquals(state,decision.state());assertFalse(decision.prepare());
        if(!text.matches("(?s).*[:：].*")){assertFalse(DialogueRouter.safe(text));assertFalse(DialogueRouter.safeContent(text));}
    }
    @Test void observationCandidateOnlyRelaxesTheExistingQuestionVeto(){
        assertFalse(DialogueRouter.safe(null));assertFalse(DialogueRouter.safeContent(null));
        assertFalse(DialogueRouter.safe("打印时没有什么异常声音"));assertTrue(DialogueRouter.safeContent("打印时没有什么异常声音"));
        assertTrue(DialogueRouter.safe("屏幕正常"));assertTrue(DialogueRouter.safeContent("屏幕正常"));
    }
    @ParameterizedTest @ValueSource(strings={"现场：屏幕正常","现场补充信息：屏幕显示字段校验错误","现场：屏幕正常；联系人：小王，然后提交",
        "现场：屏幕正常；联系人：小王；联系人：小李","现场：屏幕正常；；联系人：小王","屏幕正常；联系人：小王"})
    void explicitLabelsKeepTheirAtomicPathBeforeSemantics(String text){
        var state=route("反复卡纸，请准备报修",null).state();assertFalse(semantic(text,state).semantic());
    }
    @Test void completeRulesCommandsAndRepeatedPreparationKeepTheirExistingPriority(){
        var state=route("反复卡纸，请准备报修",null).state();
        for(String text:List.of("你好","查看工单","取消填写报修","暂停填写","继续填写报修","请准备报修","出纸口卡住，请准备报修","设备昨天实际完成多少个打印作业？"))
            assertFalse(semantic(text,state).semantic());
        var rule=semantic("联系方式一定要填手机号吗",state);assertFalse(rule.semantic());assertNotNull(rule.topic());assertEquals("PAUSED",rule.state().phase());
        var initial=semantic("反复卡纸，请准备报修",null);assertFalse(initial.semantic());assertEquals("COLLECTING",initial.state().phase());
    }
    @Test void semanticFlagNeverEstablishesOrResumesAuthorization(){
        var initial=route("反复卡纸，请准备报修",null).state();
        var paused=route("暂停填写",initial).state();var cancelled=route("取消填写报修",initial).state();
        for(DialogueState state:Arrays.asList(null,paused,cancelled)){
            var decision=semantic("打印时没有什么异常声音",state);assertFalse(decision.semantic());assertEquals(state,decision.state());assertFalse(decision.prepare());
        }
        var expired=DialogueRouter.route("c",1,2,"late","打印时没有什么异常声音",initial,"NONE",initial.expiresAt(),true);
        assertFalse(expired.semantic());assertEquals("EXPIRED",expired.state().phase());assertEquals(initial.values(),expired.state().values());assertFalse(expired.prepare());
    }
    @ParameterizedTest @ValueSource(strings={"ONE_OPEN","SUBMITTED","UNKNOWN","AMBIGUOUS"})
    void anyExistingOrUncertainTargetBlocksSemanticCollection(String target){
        var state=route("反复卡纸，请准备报修",null).state();
        var decision=DialogueRouter.route("c",1,2,"blocked","打印时没有什么异常声音",state,target,NOW.plusMinutes(9),true);
        assertFalse(decision.semantic());assertEquals(state,decision.state());assertFalse(decision.prepare());
    }
    @Test void semanticCollectionDoesNotHandleOtherPendingFieldsOrOverwriteExistingExtra(){
        var symptom=route("请准备报修",null).state();
        var contact=route("现场：屏幕正常",route("反复卡纸，请准备报修",null).state()).state();
        var contactMethod=route("小王",contact).state();
        for(DialogueState state:List.of(symptom,contact,contactMethod))assertFalse(semantic("打印时没有什么异常声音",state).semantic());
        var inconsistent=new DialogueState("c",1,2,"COLLECTING","卡纸","已检查",null,null,"extra","authorized",NOW.plusMinutes(10),3,"last");
        assertFalse(semantic("打印时没有什么异常声音",inconsistent).semantic());
    }
    @Test void anExtraProposalNeverPreparesEvenWhenItIsTheLastMissingField(){
        var state=route("故障：卡纸；联系人：小王；联系方式：123；请准备报修",null).state();assertEquals(List.of("extra"),state.missing());
        semanticProposal(state,semantic("屏幕正常，已检查。",state));
    }
}

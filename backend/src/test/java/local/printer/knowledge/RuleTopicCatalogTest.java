package local.printer.knowledge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class RuleTopicCatalogTest {
    @ParameterizedTest @ValueSource(strings={"报修要准备什么","报修需要填写哪些信息","你好，报修需要哪些资料","联系人能填多少字","联系方式一定要填写手机号吗","草稿字段错误是什么意思"})
    void recognizesBoundedTopics(String text){assertNotNull(RuleTopicCatalog.identify(text));}
    @ParameterizedTest @ValueSource(strings={"怎么拆机清除卡纸","报修能否保修换件","报修多久能到","报修多少钱","报修","请准备报修","报修要准备什么，然后提交","报修要准备什么，同时把联系人改成小王","故障描述：卡纸","售后联系方式电话是多少","现场信息屏幕报错怎么修","报修要准备什么零件"})
    void unrelatedAndMixedMutationsAreNotFieldAnswers(String text){assertNull(RuleTopicCatalog.identify(text));}
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "报修要准备什么|REQUIRED_FIELDS", "报修需要填写哪些信息。|REQUIRED_FIELDS", "您好，报修需要哪些资料？|REQUIRED_FIELDS",
        "联系人能填多少字|FIELD_LENGTH", "联系人和联系方式最多可以填写多少字？|FIELD_LENGTH", "谢谢，联系人最多能填多少字！|FIELD_LENGTH",
        "联系方式一定要填写手机号吗|CONTACT_FORMAT", "联系方式一定要填手机号吗？|CONTACT_FORMAT", "联系方式怎么填|CONTACT_FORMAT",
        "草稿字段错误是什么意思|FIELD_ERRORS", "报修字段报错怎么办？|FIELD_ERRORS", "字段校验错误怎么处理。|FIELD_ERRORS"
    })
    void completeQuestionsKeepTheirExistingTopicAndLocator(String text,String expected){
        var topic=RuleTopicCatalog.Topic.valueOf(expected);
        assertEquals(topic,RuleTopicCatalog.identify(text));assertEquals(topic,RuleTopicCatalog.broadMatch(text));
        assertEquals("business-supplement-draft-fields",topic.documentKey());
        assertEquals("自编模拟企业规则：报修草稿字段格式与校验 > "+(topic==RuleTopicCatalog.Topic.FIELD_ERRORS?"字段错误与其他拒绝的区别":"四个文本字段的格式与上限"),topic.section());
    }
    @ParameterizedTest @ValueSource(strings={"字段校验错误","字段 INVALID_ARGUMENT","联系人长度","联系方式格式","报修必填字段",
        "不是在问报修要准备什么","不要回答联系方式一定要填手机号吗","他说联系方式一定要填手机号吗","“联系方式一定要填写手机号吗”",
        "如果报修要准备什么","报修要准备什么，先别回答","请解释：草稿字段错误是什么意思","现场补充信息：屏幕显示字段校验错误",
        "报修要准备什么；联系人：小王","联系人能填多少字，立刻建单","报修要准备什么\n再解释一次"})
    void coarseSignalsCannotSelectKnowledgeByThemselves(String text){
        assertNotNull(RuleTopicCatalog.broadMatch(text));assertNull(RuleTopicCatalog.identify(text));
    }
    @ParameterizedTest @ValueSource(strings={"怎么拆机清除卡纸","报修能否保修换件","报修多久能到","报修多少钱","报修","请准备报修",
        "报修要准备什么，然后提交","报修要准备什么，同时把联系人改成小王","故障描述：卡纸","售后联系方式电话是多少","现场信息屏幕报错怎么修","报修要准备什么零件",
        "字段校验错误，维修","字段 INVALID_ARGUMENT，然后提交","小王"})
    void legacyExclusionsAndNoMatchRemainNoMatch(String text){assertNull(RuleTopicCatalog.broadMatch(text));assertNull(RuleTopicCatalog.identify(text));}
    @Test void nullAndBlankHaveNoTopic(){
        assertNull(RuleTopicCatalog.broadMatch(null));assertNull(RuleTopicCatalog.identify(null));
        assertNull(RuleTopicCatalog.broadMatch("  "));assertNull(RuleTopicCatalog.identify("  "));
    }
}

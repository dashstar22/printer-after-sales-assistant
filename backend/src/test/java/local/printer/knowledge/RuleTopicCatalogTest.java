package local.printer.knowledge;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class RuleTopicCatalogTest {
    @ParameterizedTest @ValueSource(strings={"报修要准备什么","报修需要填写哪些信息","你好，报修需要哪些资料","联系人能填多少字","联系方式一定要填写手机号吗","草稿字段错误是什么意思"})
    void recognizesBoundedTopics(String text){assertNotNull(RuleTopicCatalog.identify(text));}
    @ParameterizedTest @ValueSource(strings={"怎么拆机清除卡纸","报修能否保修换件","报修多久能到","报修多少钱","报修","请准备报修","报修要准备什么，然后提交","报修要准备什么，同时把联系人改成小王","故障描述：卡纸","售后联系方式电话是多少","现场信息屏幕报错怎么修","报修要准备什么零件"})
    void unrelatedAndMixedMutationsAreNotFieldAnswers(String text){assertNull(RuleTopicCatalog.identify(text));}
}

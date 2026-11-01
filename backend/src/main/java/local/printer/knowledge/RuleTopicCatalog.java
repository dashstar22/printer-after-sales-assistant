package local.printer.knowledge;

import java.util.*;

/** Locators only. All answer text and numeric rules must come from admitted published chunks. */
public final class RuleTopicCatalog {
    private RuleTopicCatalog(){}
    public enum Topic {
        REQUIRED_FIELDS("四个文本字段的格式与上限"), FIELD_LENGTH("四个文本字段的格式与上限"),
        CONTACT_FORMAT("四个文本字段的格式与上限"), FIELD_ERRORS("字段错误与其他拒绝的区别");
        final String section;
        Topic(String section){this.section=section;}
        public String section(){return "自编模拟企业规则：报修草稿字段格式与校验 > "+section;}
        public String documentKey(){return "business-supplement-draft-fields";}
    }
    public static Topic identify(String input){
        if(input==null)return null;
        String text=input.strip().replaceFirst("^(?:你好|您好|谢谢)[，,。！!\\s]+","");
        // Never treat a mixed mutation or a repair/warranty question as a field-rule answer.
        if(text.matches("(?s).*(?:拆机|拆卸|换件|维修|零件|耗材|碳粉|墨盒|保修|质保|改成|改为|请准备|我要报修|帮我报修|提交|删除|取消|然后|同时).*"))return null;
        boolean field=text.matches("(?s).*(?:报修|草稿|故障描述|现场补充|现场信息|联系人|联系方式|联系方法|字段).*" );
        if(!field)return null;
        if(text.matches("(?s).*(?:字段|填写|填错|校验|INVALID_ARGUMENT|400).*" )
            &&text.matches("(?s).*(?:INVALID_ARGUMENT|校验|验证|报错|错误|超长|填错|拒绝|400|409).*"))return Topic.FIELD_ERRORS;
        if(text.matches("(?s).*(?:最多|上限|长度|字数|多少字|字符|码点|能填多长|可以写多长).*"))return Topic.FIELD_LENGTH;
        if(text.matches("(?s).*(?:联系方式|联系方法).*" )&&text.matches("(?s).*(?:格式|怎么填|如何填|必须|一定要|只能|能不能|可不可以|能填|可以填|支持|是不是|是手机号吗|是手机号码吗).*"))return Topic.CONTACT_FORMAT;
        if(text.matches("(?s).*(?:报修|草稿).*(?:要准备什么|需要准备什么|准备哪些|需要哪些|填写哪些|填哪些|填写什么|填什么|要填什么|需要什么|必填|哪些信息).*"))return Topic.REQUIRED_FIELDS;
        return null;
    }
}

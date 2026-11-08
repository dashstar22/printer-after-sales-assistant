package local.printer.assistant;

import java.util.regex.Pattern;

/** Bind read-only retrieval to trusted current text, never to generated facts/history. */
final class KnowledgeQueryBinding {
    private static final Pattern LONG_NUMBER=Pattern.compile("[0-9]{7,}");
    private static final Pattern CAPACITY_UNIT=Pattern.compile("^\\s*(?:字节|bytes?|[kmgt]i?b)(?![a-zA-Z0-9_])",Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVATE_NUMBER_CONTEXT=Pattern.compile("(?:电话|手机号|联系方式|账号|帐号|卡号|身份证|订单号|序列号|编号|用户ID|设备ID|标识)[^。！？；;\\r\\n]*$",Pattern.CASE_INSENSITIVE);
    private KnowledgeQueryBinding() {}

    static String select(String current,String proposed) {
        // For an explicit, non-private device/rule question, model query wording is unnecessary.
        // In particular punctuation changes or paraphrases cannot make a valid user question vanish.
        if(ServiceDialoguePolicy.requiresKnowledge(current) && safe(current))return current;
        // Other dialogue paths retain the exact-current-substring contract.
        return proposed!=null && safe(proposed) && current!=null && current.contains(proposed)?proposed:null;
    }

    static boolean safe(String text) {
        return text!=null && !text.isBlank() && text.codePointCount(0,text.length())<=1000
            && !text.matches("(?is).*(?:@|(?:联系人|联系方式|电话|手机号码|邮箱|地址|序列号|订单号|账号|帐号|卡号|身份证|作业编号|用户ID|设备ID|标识)[：:].*|(?:邮箱|电话|联系方式|地址|联系人)(?:是|为)(?!否|什么|何|多少|必须|必填|可选|空).+|(?:电话(?:号码)?|手机号(?:码)?|联系方式|账号|帐号|卡号|身份证|订单号|序列号|作业编号|用户ID|设备ID|标识)\\s*(?:[：:=]|是|为)?\\s*[a-z0-9]).*" )
            && safeNumbers(text)
            && text.codePoints().noneMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t');
    }

    private static boolean safeNumbers(String text){
        var numbers=LONG_NUMBER.matcher(text);
        while(numbers.find()){
            // A storage measurement is not an identifier. Keep the user's exact value/unit;
            // never grant this exception to bare digits or digits embedded in an identifier.
            if(PRIVATE_NUMBER_CONTEXT.matcher(text.substring(0,numbers.start())).find())return false;
            if(numbers.start()>0 && (Character.isLetterOrDigit(text.charAt(numbers.start()-1))
                    && text.charAt(numbers.start()-1)<128 || "_+-".indexOf(text.charAt(numbers.start()-1))>=0))return false;
            if(!CAPACITY_UNIT.matcher(text.substring(numbers.end())).find())return false;
        }
        return true;
    }
}

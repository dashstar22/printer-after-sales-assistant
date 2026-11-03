package local.printer.administration;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class AdminInput {
    private AdminInput() {}
    public static void fields(JsonNode body, String... keys) {
        if(body==null || !body.isObject() || !body.propertyNames().equals(Set.of(keys)))
            throw AdminFailure.invalid("字段不完整或包含未允许字段");
    }
    public static String text(JsonNode body,String key,int max) {
        var n=body.get(key);
        if(n==null || !n.isString()) throw AdminFailure.invalid(key+" 必须为文本");
        return text(n.asString(),key,max);
    }
    public static String text(String value,String key,int max) {
        if(value==null || value.strip().isEmpty() || value.codePointCount(0,value.length())>max
            || value.codePoints().anyMatch(Character::isISOControl)) throw AdminFailure.invalid(key+" 长度或格式不正确");
        return value.strip();
    }
    public static long number(JsonNode body,String key) {
        var n=body.get(key);
        if(n==null || !n.isIntegralNumber() || !n.canConvertToLong() || n.asLong()<0) throw AdminFailure.invalid(key+" 必须为非负整数");
        return n.asLong();
    }
    public static boolean bool(JsonNode body,String key) {
        var n=body.get(key); if(n==null || !n.isBoolean()) throw AdminFailure.invalid(key+" 必须为布尔值"); return n.asBoolean();
    }
    public static String uuid(String value) {
        try { if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException(); return value; }
        catch(RuntimeException e){throw AdminFailure.invalid("requestId 必须为标准 UUID");}
    }
    public static String password(JsonNode body,String key) {
        var n=body.get(key); if(n==null || !n.isString())throw AdminFailure.invalid("密码格式不正确");
        return password(n.asString());
    }
    public static String password(String p) {
        if(p==null || p.isBlank() || p.codePointCount(0,p.length())<8 || p.codePointCount(0,p.length())>64
            || p.getBytes(StandardCharsets.UTF_8).length>72 || p.codePoints().anyMatch(Character::isISOControl))
            throw AdminFailure.invalid("密码须为8至64位有效字符且UTF-8不超过72字节");
        return p;
    }
    public static String modelKey(String s) {
        return text(Normalizer.normalize(s,Normalizer.Form.NFKC).strip().toUpperCase(Locale.ROOT),"modelCode",64);
    }
}

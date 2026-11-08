package local.printer.assistant;

import java.util.List;

/** This application has no device telemetry connection. Manuals cannot supply an actual local count. */
final class DeviceObservationPolicy {
    private DeviceObservationPolicy() {}

    static boolean needsObservedCount(String text){
        if(text==null || !text.matches("(?s).*(?:设备|打印机|机器|这台|本台).*")
                || !text.matches("(?s).*(?:今天|昨天|前天|刚才|当前|现在|此刻|实际|目前|已经|累计).*")
                || !text.matches("(?s).*(?:打印|复印|作业|任务|页数|计数|待写入数).*")
                || !text.matches("(?s).*(?:多少|几(?:个|页|次)|报出|告诉我|查一下|查询).*") )return false;
        // Keep instructions, general rules, supplied observations and mixed/write requests on their existing routes.
        if(text.matches("(?is).*(?:怎么|怎样|如何|哪里|哪儿|路径|入口|步骤|方法|含义|表示|代表|说明|规则|样例|上限|最大|最多|至少|限制|单位|换算|多久|多长|保留|保存|清零|重置|计算|统计口径|应该|能否|可以|报修|草稿|提交|确认|改成|改为|联系人|联系方式|电话|邮箱|地址|序列号|订单|工单|忽略|指令|另外|以及|然后|同时|字节|bytes?|[kmgt]i?b).*") )return false;
        if(text.matches("(?s).*(?:显示|看到|读到|读数|计数|完成|打印).{0,12}[0-9零一二三四五六七八九十百千万]+\\s*(?:个|页|次|项).*"))return false;
        return true;
    }

    static EvidenceAnswerPolicy.Answer missingCount(){
        return new EvidenceAnswerPolicy.Answer("NEEDS_INPUT",
            "我目前没有接入这台设备的实时读数或作业历史，无法确认它实际完成的数量，也不会替你估计。请查看设备面板或作业记录，补充显示的计数和对应时间范围；只提供不含个人信息的读数即可。",List.of());
    }
}

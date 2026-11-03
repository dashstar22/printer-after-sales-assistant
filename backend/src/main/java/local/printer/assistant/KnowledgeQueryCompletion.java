package local.printer.assistant;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Narrow, deterministic reference completion. It never supplies workflow fields or authorization. */
final class KnowledgeQueryCompletion {
    record Query(String query,String reason,boolean clarification,int rewriteCalls) {}
    static Query resolve(String input,List<ConversationMapper.Message> history) {
        String text=DialogueRouter.businessText(input);
        if(!text.matches("^(?:那|这个|它|上述|刚才)(?:具体)?(?:怎么|如何|该怎么|要怎么|具体怎么).{0,25}[？?。]?$"))
            return new Query(null,"DIRECT_INPUT",false,0);
        if(!safe(text))return unclear();
        // Only the immediately preceding completed knowledge turn of this owned, fixed-device conversation.
        for(int i=history.size()-2;i>=1;i--){
            var a=history.get(i);var u=history.get(i-1);
            if(!"ASSISTANT".equals(a.role())||!"USER".equals(u.role())||!a.turnId().equals(u.turnId()))continue;
            try {
                String status=JsonMapper.builder().build().readTree(a.responseJson()).path("status").asString();
                if(!Set.of("ANSWERED","NO_EVIDENCE","DEGRADED","EXCERPT_ONLY").contains(status)
                    ||!JsonMapper.builder().build().readTree(a.responseJson()).path("citations").isArray()
                    ||JsonMapper.builder().build().readTree(a.responseJson()).path("citations").isEmpty()||!safe(u.content())
                    ||u.content().matches("(?s).*(?:或者|还是|以及|另外|换设备|另一台|那台|其他型号).*")
                    ||java.time.Duration.between(a.createdAt(),history.get(history.size()-1).createdAt()).abs().toMinutes()>10)return unclear();
                String query=u.content()+"；"+text;
                return query.codePointCount(0,query.length())<=1000?new Query(query,"PREVIOUS_KNOWLEDGE_TURN",false,0):unclear();
            }catch(RuntimeException invalid){return unclear();}
        }
        return unclear();
    }
    private static boolean safe(String text){return KnowledgeQueryBinding.safe(text) && !text.matches("(?s).*(?:联系人|联系方式|电话|邮箱|地址|序列号|订单号|报修|草稿|提交|确认|忽略|指令|切换|换设备).*");}
    private static Query unclear(){return new Query(null,"AMBIGUOUS_REFERENCE",true,0);}
}

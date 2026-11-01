package local.printer.assistant;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import local.printer.knowledge.RuleTopicCatalog;

/** Three execution layers; intent is independent of the layer and never grants a tool permission. */
final class DialogueRouter {
    private DialogueRouter(){}
    record Decision(String layer,String intent,String reason,DialogueState state,
                    EvidenceAnswerPolicy.Answer answer,boolean prepare,RuleTopicCatalog.Topic topic){}
    private static final Map<String,String> LABELS=Map.of("故障描述","symptom","故障","symptom","现场补充信息","extra","现场","extra","联系人","contact","联系方式","contactMethod");
    private static final Pattern LABEL=Pattern.compile("(?:^|[；;\\n])\\s*(故障描述|故障|现场补充信息|现场|联系人|联系方式)[：:]");
    private static final Pattern CREATE=Pattern.compile("^(.*?)(?:请准备草稿|请准备报修|我要报修|申请报修|帮我报修)[。！!]?$",Pattern.DOTALL);
    static Decision route(String id,long customer,long order,String turn,String input,DialogueState previous,String target,LocalDateTime now){
        String text=businessText(input);DialogueState state=previous;
        if(state!=null&&Set.of("COLLECTING","PAUSED").contains(state.phase())&&!now.isBefore(state.expiresAt()))state=state.next("EXPIRED",state.values(),turn);
        var local=ServiceDialoguePolicy.localReply(input.strip());
        if(local!=null){
            String intent=text.matches(".*(?:天气|下雨).*")?"OUT_OF_DOMAIN":text.matches(".*(?:功能|做什么|怎么使用|你是谁).*")?"CAPABILITIES":"GREETING";
            return new Decision("RULE",intent,"COMPLETE_SERVICE_EXPRESSION",state,local,false,null);
        }
        if(Set.of("查看工单","我的工单","查看报修进度","先查看已有工单","查看已有工单").contains(clean(text))){
            if(state!=null&&state.active(now)&&"COLLECTING".equals(state.phase()))state=state.next("PAUSED",state.values(),turn);
            return answer("RULE","TICKET_NAVIGATION","COMPLETE_NAVIGATION",state,"ANSWERED","请打开“我的工单”，选择已有工单查看详情、状态和处理记录。");
        }
        if(Set.of("取消报修","取消填写","取消填写报修","停止填写报修","取消报修，别保存").contains(clean(text))){
            if(state!=null)state=state.next("CANCELLED",state.values(),turn);
            return answer("STATE","CANCEL","EXPLICIT_CANCEL",state,"ANSWERED","已停止本次信息收集。需要时可以重新告诉我准备报修，已有草稿和工单可继续在页面查看。");
        }
        var topic=RuleTopicCatalog.identify(text);
        if(topic!=null){
            if(state!=null&&state.active(now)&&"COLLECTING".equals(state.phase()))state=state.next("PAUSED",state.values(),turn);
            return new Decision("RULE","KNOWLEDGE","RULE_TOPIC_"+topic.name(),state,null,false,topic);
        }
        var create=CREATE.matcher(text);
        if("NONE".equals(target)&&create.matches()){
            String rawPrefix=create.group(1);
            if(!rawPrefix.isEmpty()&&!rawPrefix.matches("(?s).*[，,；;。\\r\\n]\\s*$"))
                return answer("STATE","CLARIFY","AMBIGUOUS_START",state,"NEEDS_INPUT","请明确告诉我是否要准备本次报修；如果需要，可以回复“请准备报修”。");
            String prefix=rawPrefix.replaceFirst("[，,；;。\\s]+$","");
            List<String> values=new ArrayList<>(Arrays.asList(null,null,null,null));
            if(!prefix.isEmpty()){
                var labeled=labeled(prefix);
                if(labeled!=null)apply(values,labeled);
                else if(safe(prefix))values.set(0,AssistantConversationService.normalized(prefix));
                else return answer("STATE","CLARIFY","AMBIGUOUS_START",state,"NEEDS_INPUT","请先描述实际故障，再明确告诉我准备报修；我不会猜填或执行消息中的其他操作。");
            }
            state=new DialogueState(id,customer,order,"COLLECTING",values.get(0),values.get(1),values.get(2),values.get(3),null,turn,now.plusMinutes(10),previous==null?1:previous.revision()+1,turn);
            // Select the first missing field without advancing the initial revision twice.
            state=new DialogueState(id,customer,order,state.phase(),state.symptom(),state.extra(),state.contact(),state.contactMethod(),state.missing().stream().findFirst().orElse(null),turn,state.expiresAt(),state.revision(),turn);
            return collecting(state,"EXPLICIT_PREPARATION");
        }
        if(Set.of("继续填写报修","继续填写","恢复填写报修").contains(clean(text))){
            if(state==null||!state.active(now)||!"NONE".equals(target))return inactive(state);
            state=state.next("COLLECTING",state.values(),turn);return collecting(state,"EXPLICIT_RESUME");
        }
        if(state!=null&&"EXPIRED".equals(state.phase())&&!question(text))return inactive(state);
        if(state!=null&&state.active(now)&&"NONE".equals(target)){
            if(ServiceDialoguePolicy.requiresKnowledge(text)||question(text)){
                state=state.next("PAUSED",state.values(),turn);
                return new Decision("MODEL","KNOWLEDGE","QUESTION_PAUSES_COLLECTION",state,null,false,null);
            }
            if("PAUSED".equals(state.phase()))return answer("STATE","CLARIFY","PAUSED_REQUIRES_RESUME",state,"NEEDS_INPUT","报修信息已保留。回复“继续填写报修”可以接着补充，也可以继续提问。");
            if(Set.of("暂停填写","暂停报修","晚点再说","这个晚点再说").contains(clean(text))){
                state=state.next("PAUSED",state.values(),turn);
                return answer("STATE","PAUSE","EXPLICIT_PAUSE",state,"NEEDS_INPUT","已暂停填写，之前提供的信息已保留。需要时回复“继续填写报修”。");
            }
            var fields=labeled(text);var values=new ArrayList<>(state.values());
            if(fields!=null)apply(values,fields);
            else if(state.pendingField()!=null&&safe(text)&&!text.matches("(?s).*[:：].*")
                &&!(Set.of("contact","contactMethod").contains(state.pendingField())&&Set.of("无","没有","不提供").contains(clean(text))))values.set(DialogueState.FIELDS.indexOf(state.pendingField()),AssistantConversationService.normalized(text));
            else return answer("STATE","CLARIFY","AMBIGUOUS_FIELD",state,"NEEDS_INPUT","我不会猜测或代填。"+AssistantConversationService.missingPrompt(state.pendingField()==null?DialogueState.FIELDS:List.of(state.pendingField()))+"\n也可以回复“暂停填写”或“取消填写报修”。");
            state=state.next("COLLECTING",values,turn);return collecting(state,"LITERAL_FIELD_COLLECTION");
        }
        return new Decision("MODEL",AssistantConversationService.edit(text)!=null?"EDIT":"UNRESOLVED","NATURAL_LANGUAGE_FALLBACK",state,null,false,null);
    }
    private static Decision inactive(DialogueState state){return answer("STATE","CLARIFY","AUTHORIZATION_INACTIVE",state,"NEEDS_INPUT","本次填写已结束或超过有效时间。请重新告诉我“请准备报修”，再核对本次要提供的信息。");}
    private static Decision collecting(DialogueState state,String reason){
        if(state.missing().isEmpty())return new Decision("STATE","PREPARE_REPAIR",reason,state,null,true,null);
        return answer("STATE","COLLECT_FIELDS",reason,state,"NEEDS_INPUT",AssistantConversationService.missingPrompt(List.of(state.pendingField())));
    }
    private static Decision answer(String layer,String intent,String reason,DialogueState state,String status,String text){return new Decision(layer,intent,reason,state,new EvidenceAnswerPolicy.Answer(status,text,List.of()),false,null);}
    private static String clean(String value){return value.replaceFirst("[。！!\\s]+$","");}
    static String businessText(String value){return value.strip().replaceFirst("^(?:你好|您好|谢谢)[，,。！!\\s]+","");}
    static boolean question(String value){return value.matches("(?s).*(?:[？?]|什么|多少|是否|能否|怎么|如何|为啥|为什么|可以吗|行吗|对吗|吗[。！!]?|呢[。！!]?|要不要).*" );}
    static boolean safe(String value){return value!=null&&!value.isBlank()&&!Set.of("不","否","不用","没想好","算了").contains(clean(value))&&!question(value)
        &&!value.matches("(?s).*(?:你帮我猜|猜|推测|编造|代填|随便|不知道|不清楚|不确定|不填|不申请|改成|改为|保存|修改|提交|确认|删除|清空|取消|暂停|晚点|再说|不要|不想|别填|不用填|请|然后|同时|如果|假如|帮我|报修|准备草稿|他说|她说|引用|示例).*" );}
    private static Map<String,String> labeled(String text){
        var matcher=LABEL.matcher(text);var keys=new ArrayList<String>();var starts=new ArrayList<Integer>();var ends=new ArrayList<Integer>();
        while(matcher.find()){keys.add(LABELS.get(matcher.group(1)));starts.add(matcher.start());ends.add(matcher.end());}
        if(keys.isEmpty()||starts.get(0)!=0)return null;
        var result=new LinkedHashMap<String,String>();
        for(int i=0;i<keys.size();i++){
            String value=AssistantConversationService.normalized(text.substring(ends.get(i),i+1<keys.size()?starts.get(i+1):text.length()));
            if(!safe(value)||result.putIfAbsent(keys.get(i),value)!=null)return null;
        }
        return result;
    }
    private static void apply(List<String> values,Map<String,String> fields){fields.forEach((key,value)->values.set(DialogueState.FIELDS.indexOf(key),value));}
}

package local.printer.assistant;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import local.printer.knowledge.RuleTopicCatalog;

/** Three execution layers; intent is independent of the layer and never grants a tool permission. */
final class DialogueRouter {
    private DialogueRouter(){}
    record Decision(String layer,String intent,String reason,DialogueState state,
                    EvidenceAnswerPolicy.Answer answer,boolean prepare,RuleTopicCatalog.Topic topic,boolean semantic,
                    boolean collectionSemantic,Map<String,String> candidates){
        Decision {
            candidates=Map.copyOf(candidates);
        }
        Decision(String layer,String intent,String reason,DialogueState state,EvidenceAnswerPolicy.Answer answer,boolean prepare,RuleTopicCatalog.Topic topic,boolean semantic){
            this(layer,intent,reason,state,answer,prepare,topic,semantic,false,Map.of());
        }
        Decision(String layer,String intent,String reason,DialogueState state,EvidenceAnswerPolicy.Answer answer,boolean prepare,RuleTopicCatalog.Topic topic){
            this(layer,intent,reason,state,answer,prepare,topic,false);
        }
        @Override public String toString(){
            return "Decision[layer="+layer+", intent="+intent+", reason="+reason+", candidateFields="+candidates.keySet()+"]";
        }
    }
    private static final Map<String,String> LABELS=Map.of("故障描述","symptom","故障","symptom","现场补充信息","extra","现场","extra","联系人","contact","联系方式","contactMethod");
    private static final Pattern LABEL=Pattern.compile("^(故障描述|故障|现场补充信息|现场|联系人|联系方式)[：:]");
    private record Labeled(boolean recognized,Map<String,String> fields){}
    private static final Pattern CREATE=Pattern.compile("^(.*?)(?:请准备草稿|请准备报修|我要报修|申请报修|帮我报修)[。！!]?$",Pattern.DOTALL);
    /** Production entry: literal syntax is a proposal, never proof that a field is meaningful. */
    static Decision routeControlled(String id,long customer,long order,String turn,String input,DialogueState previous,String target,
                                    LocalDateTime now,boolean observationSemantics,boolean collectionSemantics){
        var base=route(id,customer,order,turn,input,previous,target,now,observationSemantics&&!collectionSemantics);
        String text=businessText(input);
        if("NONE".equals(target)){
            var create=CREATE.matcher(text);
            if(create.matches()&&"EXPLICIT_PREPARATION".equals(base.reason())&&(previous==null||!previous.active(now))){
                String raw=create.group(1);
                if(!raw.isEmpty()&&!raw.matches("(?s).*[，,；;。\\r\\n]\\s*$"))return base;
                String prefix=raw.replaceFirst("[，,；;。\\s]+$","");
                var parsed=literalCandidates(prefix);
                if(parsed.recognized()&&parsed.fields()==null)return clarify(previous,"INVALID_LABELED_FIELDS");
                Map<String,String> candidates=prefix.isEmpty()?Map.of():parsed.recognized()?parsed.fields():Map.of("symptom",normalizedLiteral(prefix));
                var blank=new DialogueState(id,customer,order,"COLLECTING",null,null,null,null,"symptom",turn,
                    now.plusMinutes(10),previous==null?1:previous.revision()+1,turn);
                if(candidates.isEmpty())return collecting(blank,"EXPLICIT_PREPARATION");
                return collectionProposal(blank,candidates,collectionSemantics);
            }
            if(previous!=null&&previous.active(now)&&"COLLECTING".equals(previous.phase())
                    && ("LITERAL_FIELD_COLLECTION".equals(base.reason())||"AMBIGUOUS_FIELD".equals(base.reason())
                        ||"AMBIGUOUS_COLLECTION_INPUT".equals(base.reason())||"INVALID_LABELED_FIELDS".equals(base.reason()))){
                var parsed=literalCandidates(text);
                if(parsed.recognized()&&parsed.fields()==null)return clarify(previous,"INVALID_LABELED_FIELDS");
                if(previous.pendingField()==null&&!parsed.recognized())return clarify(previous,"COLLECTION_ALREADY_COMPLETE");
                var candidates=parsed.recognized()?parsed.fields():Map.of(previous.pendingField(),normalizedLiteral(text));
                return collectionProposal(previous,candidates,collectionSemantics);
            }
        }
        if(collectionSemantics&&"NATURAL_LANGUAGE_FALLBACK".equals(base.reason())&&"UNRESOLVED".equals(base.intent()))
            return new Decision("MODEL","UNRESOLVED","COLLECTION_SCOPE_PROPOSAL",base.state(),null,false,null,false,true,Map.of());
        return base;
    }
    private static String normalizedLiteral(String value){return AssistantConversationService.normalized(value);}
    private static Decision collectionProposal(DialogueState state,Map<String,String> candidates,boolean enabled){
        if(!enabled)return new Decision("STATE","CLARIFY","COLLECTION_DISABLED",state,
            new EvidenceAnswerPolicy.Answer("NEEDS_INPUT","当前对话内容校验未开启，本轮没有填入报修信息。请使用普通报修入口填写并核对内容。",List.of()),false,null);
        return new Decision("MODEL","COLLECT_FIELDS","COLLECTION_SEMANTIC_PROPOSAL",state,null,false,null,false,true,candidates);
    }
    static Decision collectionClarification(DialogueState state,String reason){
        String prompt=state==null||state.pendingField()==null?"请说明设备遇到的问题，或明确告诉我需要进行的售后操作。":
            AssistantConversationService.missingPrompt(List.of(state.pendingField()));
        return new Decision("MODEL","CLARIFY",reason,state,new EvidenceAnswerPolicy.Answer("NEEDS_INPUT",
            "本轮没有填入报修信息。"+prompt,List.of()),false,null);
    }
    /** Parse only syntax. Relevance is decided later and no field is applied here. */
    private static Labeled literalCandidates(String text){
        String[] segments=text.split("\\r\\n|[；;\\r\\n]",-1);
        boolean recognized=Arrays.stream(segments).anyMatch(segment->LABEL.matcher(normalizedLiteral(segment)).find());
        if(!recognized)return new Labeled(false,null);
        int end=segments.length;while(end>0&&normalizedLiteral(segments[end-1]).isEmpty())end--;
        var result=new LinkedHashMap<String,String>();
        for(int i=0;i<end;i++){
            String segment=normalizedLiteral(segments[i]);var matcher=LABEL.matcher(segment);
            if(!matcher.find())return new Labeled(true,null);
            String field=LABELS.get(matcher.group(1)),value=normalizedLiteral(segment.substring(matcher.end()));
            if(value.isEmpty()||result.putIfAbsent(field,value)!=null)return new Labeled(true,null);
        }
        return new Labeled(true,result);
    }
    static Decision route(String id,long customer,long order,String turn,String input,DialogueState previous,String target,LocalDateTime now){
        return route(id,customer,order,turn,input,previous,target,now,false);
    }
    static Decision route(String id,long customer,long order,String turn,String input,DialogueState previous,String target,LocalDateTime now,boolean observationSemantics){
        String text=businessText(input);DialogueState state=previous;
        if(state!=null&&Set.of("COLLECTING","PAUSED").contains(state.phase())&&!now.isBefore(state.expiresAt()))state=state.next("EXPIRED",state.values(),turn);
        if(DeviceObservationPolicy.needsObservedCount(text)){
            if(state!=null&&state.active(now)&&"COLLECTING".equals(state.phase()))state=state.next("PAUSED",state.values(),turn);
            return new Decision("RULE","DEVICE_OBSERVATION","DEVICE_READING_UNAVAILABLE",state,DeviceObservationPolicy.missingCount(),false,null);
        }
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
        var create=CREATE.matcher(text);
        if("NONE".equals(target)&&create.matches()){
            String rawPrefix=create.group(1);
            if(!rawPrefix.isEmpty()&&!rawPrefix.matches("(?s).*[，,；;。\\r\\n]\\s*$"))
                return answer("STATE","CLARIFY","AMBIGUOUS_START",state,"NEEDS_INPUT","请明确告诉我是否要准备本次报修；如果需要，可以回复“请准备报修”。");
            String prefix=rawPrefix.replaceFirst("[，,；;。\\s]+$","");
            List<String> values=new ArrayList<>(Arrays.asList(null,null,null,null));
            if(!prefix.isEmpty()){
                var labeled=labeled(prefix);
                if(labeled.recognized()){
                    if(labeled.fields()==null)return clarify(state,"INVALID_LABELED_FIELDS");
                    apply(values,labeled.fields());
                }
                else if(safe(prefix))values.set(0,AssistantConversationService.normalized(prefix));
                else return answer("STATE","CLARIFY","AMBIGUOUS_START",state,"NEEDS_INPUT","请先描述实际故障，再明确告诉我准备报修；我不会猜填或执行消息中的其他操作。");
            }
            if(state!=null&&state.active(now)){
                if(!prefix.isEmpty())return answer("STATE","CLARIFY","REPEATED_PREPARATION_WITH_CONTENT",state,"NEEDS_INPUT","本次报修信息已保留。请将要补充或修正的字段单独发送；如果要重新填写，请先取消本次填写，再明确准备报修。");
                if("PAUSED".equals(state.phase()))return paused(state);
                if(state.missing().isEmpty())return answer("STATE","CLARIFY","REPEATED_PREPARATION_COMPLETE",state,"NEEDS_INPUT","报修信息已收集完整，请先核对原结果；需要修正时，请单独发送带标签的字段。我不会自动再次保存。");
                return answer("STATE","COLLECT_FIELDS","REPEATED_PREPARATION",state,"NEEDS_INPUT",AssistantConversationService.missingPrompt(List.of(state.pendingField())));
            }
            state=new DialogueState(id,customer,order,"COLLECTING",values.get(0),values.get(1),values.get(2),values.get(3),null,turn,now.plusMinutes(10),previous==null?1:previous.revision()+1,turn);
            // Select the first missing field without advancing the initial revision twice.
            state=new DialogueState(id,customer,order,state.phase(),state.symptom(),state.extra(),state.contact(),state.contactMethod(),state.missing().stream().findFirst().orElse(null),turn,state.expiresAt(),state.revision(),turn);
            return collecting(state,"EXPLICIT_PREPARATION");
        }
        // Labels can override a coarse topic signal only inside an authorized collection.
        if(state!=null&&state.active(now)&&"COLLECTING".equals(state.phase())&&"NONE".equals(target)){
            var fields=labeled(text);
            if(fields.recognized()){
                if(fields.fields()==null)return clarify(state,"INVALID_LABELED_FIELDS");
                var values=new ArrayList<>(state.values());apply(values,fields.fields());
                state=state.next("COLLECTING",values,turn);return collecting(state,"LITERAL_FIELD_COLLECTION");
            }
        }
        var topic=RuleTopicCatalog.identify(text);
        if(topic!=null){
            if(state!=null&&state.active(now)&&"COLLECTING".equals(state.phase()))state=state.next("PAUSED",state.values(),turn);
            return new Decision("RULE","KNOWLEDGE","RULE_TOPIC_"+topic.name(),state,null,false,topic);
        }
        if(Set.of("继续填写报修","继续填写","恢复填写报修").contains(clean(text))){
            if(state==null||!state.active(now)||!"NONE".equals(target))return inactive(state);
            state=state.next("COLLECTING",state.values(),turn);return collecting(state,"EXPLICIT_RESUME");
        }
        if(state!=null&&"EXPIRED".equals(state.phase())&&!question(text))return inactive(state);
        if(state!=null&&state.active(now)&&"NONE".equals(target)){
            if("PAUSED".equals(state.phase())){
                if(ServiceDialoguePolicy.requiresKnowledge(text)||question(text))return new Decision("MODEL","KNOWLEDGE","QUESTION_WHILE_PAUSED",state,null,false,null);
                return paused(state);
            }
            if(RuleTopicCatalog.broadMatch(text)!=null)return clarify(state,"AMBIGUOUS_COLLECTION_INPUT");
            if(Set.of("暂停填写","暂停报修","晚点再说","这个晚点再说").contains(clean(text))){
                state=state.next("PAUSED",state.values(),turn);
                return answer("STATE","PAUSE","EXPLICIT_PAUSE",state,"NEEDS_INPUT","已暂停填写，之前提供的信息已保留。需要时回复“继续填写报修”。");
            }
            if(observationSemantics&&"extra".equals(state.pendingField())&&(state.extra()==null||state.extra().isBlank())
                &&safeContent(text)&&!text.matches("(?s).*[:：].*"))
                return new Decision("MODEL","COLLECT_FIELDS","OBSERVATION_SEMANTIC_PROPOSAL",state,null,false,null,true);
            if(ServiceDialoguePolicy.requiresKnowledge(text)||question(text))return clarify(state,"AMBIGUOUS_COLLECTION_INPUT");
            var values=new ArrayList<>(state.values());
            if(state.pendingField()!=null&&safeField(state.pendingField(),text)&&!text.matches("(?s).*[:：].*"))values.set(DialogueState.FIELDS.indexOf(state.pendingField()),AssistantConversationService.normalized(text));
            else return clarify(state,"AMBIGUOUS_FIELD");
            state=state.next("COLLECTING",values,turn);return collecting(state,"LITERAL_FIELD_COLLECTION");
        }
        return new Decision("MODEL",AssistantConversationService.edit(text)!=null?"EDIT":"UNRESOLVED","NATURAL_LANGUAGE_FALLBACK",state,null,false,null);
    }
    private static Decision paused(DialogueState state){return answer("STATE","CLARIFY","PAUSED_REQUIRES_RESUME",state,"NEEDS_INPUT","报修信息已保留。回复“继续填写报修”可以接着补充，也可以继续提问。");}
    private static Decision clarify(DialogueState state,String reason){return answer("STATE","CLARIFY",reason,state,"NEEDS_INPUT","我不会猜测或代填。"+AssistantConversationService.missingPrompt(state==null||state.pendingField()==null?DialogueState.FIELDS:List.of(state.pendingField()))+"\n也可以回复“暂停填写”或“取消填写报修”。");}
    private static Decision inactive(DialogueState state){return answer("STATE","CLARIFY","AUTHORIZATION_INACTIVE",state,"NEEDS_INPUT","本次填写已结束或超过有效时间。请重新告诉我“请准备报修”，再核对本次要提供的信息。");}
    private static Decision collecting(DialogueState state,String reason){
        if(state.missing().isEmpty())return new Decision("STATE","PREPARE_REPAIR",reason,state,null,true,null);
        return answer("STATE","COLLECT_FIELDS",reason,state,"NEEDS_INPUT",AssistantConversationService.missingPrompt(List.of(state.pendingField())));
    }
    private static Decision answer(String layer,String intent,String reason,DialogueState state,String status,String text){return new Decision(layer,intent,reason,state,new EvidenceAnswerPolicy.Answer(status,text,List.of()),false,null);}
    private static String clean(String value){return value.replaceFirst("[。！!\\s]+$","");}
    static String businessText(String value){return value.strip().replaceFirst("^(?:你好|您好|谢谢)[，,。！!\\s]+","");}
    static boolean question(String value){return value.matches("(?s).*(?:[？?]|什么|多少|是否|能否|怎么|如何|为啥|为什么|可以吗|行吗|对吗|吗[。！!]?|呢[。！!]?|要不要).*" );}
    static boolean safe(String value){return safeContent(value)&&!question(value);}
    static boolean safeContent(String value){return value!=null&&!value.isBlank()&&!Set.of("不","否","不用","没想好","算了").contains(clean(value))
        &&!value.matches("(?s).*(?:你帮我猜|猜|推测|编造|代填|随便|不知道|不清楚|不确定|不填|不申请|改成|改为|保存|修改|提交|确认|删除|清空|取消|暂停|晚点|再说|不要|不想|别填|不用填|请|然后|同时|如果|假如|帮我|报修|准备草稿|他说|她说|引用|示例).*" );}
    private static boolean safeField(String field,String value){return safe(value)
        &&!(Set.of("contact","contactMethod").contains(field)&&Set.of("无","没有","不提供").contains(clean(AssistantConversationService.normalized(value))));}
    private static Labeled labeled(String text){
        String[] segments=text.split("\\r\\n|[；;\\r\\n]",-1);
        boolean recognized=Arrays.stream(segments).anyMatch(segment->LABEL.matcher(AssistantConversationService.normalized(segment)).find());
        if(!recognized)return new Labeled(false,null);
        int end=segments.length;
        while(end>0&&AssistantConversationService.normalized(segments[end-1]).isEmpty())end--;
        var result=new LinkedHashMap<String,String>();
        for(int i=0;i<end;i++){
            String segment=AssistantConversationService.normalized(segments[i]);var matcher=LABEL.matcher(segment);
            if(!matcher.find())return new Labeled(true,null);
            String field=LABELS.get(matcher.group(1)),value=AssistantConversationService.normalized(segment.substring(matcher.end()));
            if(!safeField(field,value)||result.putIfAbsent(field,value)!=null)return new Labeled(true,null);
        }
        return new Labeled(true,result);
    }
    private static void apply(List<String> values,Map<String,String> fields){fields.forEach((key,value)->values.set(DialogueState.FIELDS.indexOf(key),value));}
}

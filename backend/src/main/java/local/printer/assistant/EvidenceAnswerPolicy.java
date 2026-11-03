package local.printer.assistant;

import java.util.*;
import local.printer.knowledge.KnowledgeRetrievalService.Source;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Model selects evidence; only the server supplies displayed facts and source metadata. */
final class EvidenceAnswerPolicy {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    record Answer(String status,String text,List<Source> citations,List<ConversationResponse.Point> points) {
        Answer(String status,String text,List<Source> citations){this(status,text,citations,List.of());}
    }
    /** Allowlisted server diagnostics only; never include model text, source IDs or exception messages. */
    enum ValidationCode { VALID, NO_EVIDENCE, INVALID_JSON, SCHEMA, UNKNOWN_SOURCE, DUPLICATE_SOURCE,
        NON_LITERAL, INCOMPLETE_SENTENCE, UNSAFE_EXCERPT, OUTPUT_LIMIT, VALIDATION_ERROR }
    record Validation(Answer answer,ValidationCode code) {}
    private static Validation invalid(ValidationCode code){return new Validation(rejected(),code);}
    static Answer noEvidence(){return new Answer("NO_EVIDENCE","暂时没有找到能支持这个问题的适用资料，当前不能确认答案。你可以补充具体问题、设备提示和已尝试的操作，我继续帮你整理；需要维修时，也可以直接填写报修信息。",List.of());}
    static Answer unavailable(){return new Answer("RETRIEVAL_UNAVAILABLE","资料查询暂时不可用，当前还不能核对处理建议。可以稍后再问；如需维修，也可以直接填写报修信息。",List.of());}
    static Answer unauthorized(){return new Answer("EVIDENCE_NOT_AUTHORIZED","找到的资料暂不可用于回答，因此还不能确认处理建议。可以直接填写报修信息，由客服继续跟进。",List.of());}
    static Answer rejected(){return new Answer("REFERENCE_REJECTED","这次回复的依据未能确认，暂时不能给出处理建议。可以补充设备上的提示或换个说法继续问；需要维修时，也可以直接填写报修信息。",List.of());}
    static Answer excerpts(List<Source> evidence,String status){
        if(evidence.isEmpty())return unavailable();
        String request=JSON.writeValueAsString(Map.of("kind","KNOWLEDGE","pointIds",evidence.stream().map(Source::pointId).toList()));
        var answer=assemble(request,evidence);if(!"ANSWERED".equals(answer.status()))return rejected();
        String prefix="EXCERPT_ONLY".equals(status)?"资料排序暂未完成，以下仅提供已核对原文，暂不能形成处理建议。可继续普通报修。\n\n":"部分资料查询服务暂不可用，以下为降级查得的已核对原文。可稍后重试或继续普通报修。\n\n";
        if((prefix+answer.text()).codePointCount(0,(prefix+answer.text()).length())>4000)return rejected();
        return new Answer(status,prefix+answer.text(),answer.citations());
    }

    static Answer assemble(String modelText,List<Source> evidence){
        return evaluate(modelText,evidence).answer();
    }
    static Validation evaluate(String modelText,List<Source> evidence){
        final tools.jackson.databind.JsonNode root;
        try {root=JSON.readTree(modelText);}catch(RuntimeException invalid){return invalid(ValidationCode.INVALID_JSON);}
        try {
            if(root==null||!root.isObject()||!root.path("kind").isString())return invalid(ValidationCode.SCHEMA);
            if(root.path("kind").asString().equals("NO_EVIDENCE") && root.propertyNames().equals(Set.of("kind")))return new Validation(noEvidence(),ValidationCode.NO_EVIDENCE);
            if(root.propertyNames().equals(Set.of("kind","points")) && "KNOWLEDGE".equals(root.path("kind").asString()))return points(root,evidence);
            if(!root.propertyNames().equals(Set.of("kind","pointIds"))||!root.path("kind").asString().equals("KNOWLEDGE"))return invalid(ValidationCode.SCHEMA);
            var ids=root.get("pointIds");if(!ids.isArray()||ids.isEmpty()||ids.size()>5)return invalid(ValidationCode.SCHEMA);
            var allowed=new LinkedHashMap<String,Source>();for(var s:evidence)allowed.put(s.pointId(),s);
            var selected=new ArrayList<Source>();var seen=new HashSet<String>();
            for(var id:ids){
                if(!id.isString())return invalid(ValidationCode.SCHEMA);
                if(!seen.add(id.asString()))return invalid(ValidationCode.DUPLICATE_SOURCE);
                if(!allowed.containsKey(id.asString()))return invalid(ValidationCode.UNKNOWN_SOURCE);
                selected.add(allowed.get(id.asString()));
            }
            var text=new StringBuilder("适用资料原文如下（请核对适用条件；自编模拟规则不构成厂商维修或保修承诺）：");
            for(int i=0;i<selected.size();i++){
                // Whole chunks preserve their recorded warnings and conditions; no model prose is displayed.
                var s=selected.get(i);text.append("\n\n[").append(i+1).append("] ").append(s.title())
                    .append("（版本：").append(s.version()).append("；适用：")
                    .append("GENERAL".equals(s.modelScope())?"通用规则":String.join("、",s.models()))
                    .append("；").append(s.simulated()?"模拟资料":"登记资料").append("；使用条件：").append(Objects.toString(s.useCondition(),"未另行登记")).append("）\n").append(s.content());
            }
            // messages.content is VARCHAR(4000); never trim away a source's safety conditions.
            if(text.codePointCount(0,text.length())>4000)return invalid(ValidationCode.OUTPUT_LIMIT);
            return new Validation(new Answer("ANSWERED",text.toString(),List.copyOf(selected)),ValidationCode.VALID);
        } catch(RuntimeException invalid){return invalid(ValidationCode.VALIDATION_ERROR);}
    }
    /** Extractive concise points: no free facts. Complete source conditions/warnings are added by the host. */
    private static Validation points(tools.jackson.databind.JsonNode root,List<Source> evidence){
        var items=root.path("points");if(!items.isArray()||items.isEmpty()||items.size()>5)return invalid(ValidationCode.SCHEMA);
        var allowed=new LinkedHashMap<String,Source>();for(var s:evidence)allowed.put(s.pointId(),s);
        var selected=new ArrayList<Source>();var points=new ArrayList<ConversationResponse.Point>();
        var body=new StringBuilder("根据本次资料摘录整理（适用条件和警告请一并核对；来源不代表厂商承诺）：");
        for(var p:items){
            if(!p.isObject()||!p.propertyNames().equals(Set.of("text","sourceIds"))||!p.path("text").isString())return invalid(ValidationCode.SCHEMA);
            String text=p.path("text").asString();var ids=p.path("sourceIds");
            if(text.isBlank()||text.length()>800||!ids.isArray()||ids.isEmpty()||ids.size()>5)return invalid(ValidationCode.SCHEMA);
            var numbers=new ArrayList<Integer>();var seen=new HashSet<String>();
            for(var id:ids){
                if(!id.isString())return invalid(ValidationCode.SCHEMA);
                if(!seen.add(id.asString()))return invalid(ValidationCode.DUPLICATE_SOURCE);
                var source=allowed.get(id.asString());
                if(source==null)return invalid(ValidationCode.UNKNOWN_SOURCE);
                if(!completeExcerpt(source.content(),text))return invalid(source.content().contains(text)?ValidationCode.INCOMPLETE_SENTENCE:ValidationCode.NON_LITERAL);
                if(unsafe(text))return invalid(ValidationCode.UNSAFE_EXCERPT);
                if(!selected.contains(source))selected.add(source);numbers.add(selected.indexOf(source)+1);
            }
            points.add(new ConversationResponse.Point(text,List.copyOf(numbers)));
            body.append("\n\n").append(text).append(" [").append(String.join(",",numbers.stream().map(String::valueOf).toList())).append("]");
        }
        for(int i=0;i<selected.size();i++){
            var source=selected.get(i);body.append("\n\n[").append(i+1).append("] 适用：").append("GENERAL".equals(source.modelScope())?"通用规则":String.join("、",source.models()))
                .append("；").append(source.title()).append("；版本：").append(source.version()).append("；").append(source.simulated()?"模拟资料":source.sourceType())
                .append("；使用条件：").append(Objects.toString(source.useCondition(),"未另行登记"));
            for(String clause:source.content().split("(?<=[。！？\n])"))if(clause.matches("(?s).*(?:警告|注意|条件|仅|禁止|不得|必须|如果|若|之前|先|断电|冷却|切勿).*"))body.append("\n").append(clause.strip());
        }
        if(body.codePointCount(0,body.length())>4000)return invalid(ValidationCode.OUTPUT_LIMIT);
        return new Validation(new Answer("ANSWERED",body.toString(),List.copyOf(selected),List.copyOf(points)),ValidationCode.VALID);
    }
    private static boolean completeExcerpt(String content,String text){
        // Whitespace between complete sentences is formatting, not part of a safety condition.
        // Never skip words/negation to manufacture a sentence boundary.
        for(int at=content.indexOf(text);at>=0;at=content.indexOf(text,at+1)){
            int before=at-1;while(before>=0 && Character.isWhitespace(content.charAt(before)))before--;
            int end=at+text.length();while(end<content.length() && Character.isWhitespace(content.charAt(end)))end++;
            if(before>=0 && content.substring(0,before+1).matches("(?s).*(?:不得|不要|不能|禁止|切勿|严禁|必须先|不|未|勿)$"))continue;
            if((before<0||"。！？".indexOf(content.charAt(before))>=0 || content.substring(before+1,at).contains("\n"))
                && (end==content.length()||"。！？\n".indexOf(text.charAt(text.length()-1))>=0))return true;
        }
        return false;
    }
    private static boolean unsafe(String text){return text.matches("(?is).*(?:忽略.{0,12}(?:指令|规则)|跳过.{0,8}确认|他人(?:数据|订单)|system\\s*:|调用.{0,12}(?:工具|接口)|猜填|猜测联系人).*");}

}

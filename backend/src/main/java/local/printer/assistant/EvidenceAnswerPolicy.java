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
    record Answer(String status,String text,List<Source> citations) {}
    static Answer noEvidence(){return new Answer("NO_EVIDENCE","暂时没有找到能支持这个问题的适用资料，当前不能确认答案。你可以补充具体问题、设备提示和已尝试的操作，我继续帮你整理；需要维修时，也可以直接填写报修信息。",List.of());}
    static Answer unavailable(){return new Answer("RETRIEVAL_UNAVAILABLE","资料查询暂时不可用，当前还不能核对处理建议。可以稍后再问；如需维修，也可以直接填写报修信息。",List.of());}
    static Answer unauthorized(){return new Answer("EVIDENCE_NOT_AUTHORIZED","找到的资料暂不可用于回答，因此还不能确认处理建议。可以直接填写报修信息，由客服继续跟进。",List.of());}
    static Answer rejected(){return new Answer("REFERENCE_REJECTED","这次回复的依据未能确认，暂时不能给出处理建议。可以补充设备上的提示或换个说法继续问；需要维修时，也可以直接填写报修信息。",List.of());}

    static Answer assemble(String modelText,List<Source> evidence){
        try {
            var root=JSON.readTree(modelText);
            if(root==null||!root.isObject()||!root.path("kind").isString())return rejected();
            if(root.path("kind").asString().equals("NO_EVIDENCE") && root.propertyNames().equals(Set.of("kind")))return noEvidence();
            if(!root.propertyNames().equals(Set.of("kind","pointIds"))||!root.path("kind").asString().equals("KNOWLEDGE"))return rejected();
            var ids=root.get("pointIds");if(!ids.isArray()||ids.isEmpty()||ids.size()>5)return rejected();
            var allowed=new LinkedHashMap<String,Source>();for(var s:evidence)allowed.put(s.pointId(),s);
            var selected=new ArrayList<Source>();var seen=new HashSet<String>();
            for(var id:ids){
                if(!id.isString()||!seen.add(id.asString())||!allowed.containsKey(id.asString()))return rejected();
                selected.add(allowed.get(id.asString()));
            }
            var text=new StringBuilder("适用资料原文如下（请核对适用条件；自编模拟规则不构成厂商维修或保修承诺）：");
            for(int i=0;i<selected.size();i++){
                // Whole chunks preserve their recorded warnings and conditions; no model prose is displayed.
                var s=selected.get(i);text.append("\n\n[").append(i+1).append("] ").append(s.title())
                    .append("（版本：").append(s.version()).append("；适用：")
                    .append("GENERAL".equals(s.modelScope())?"通用规则":String.join("、",s.models()))
                    .append("；").append(s.simulated()?"模拟资料":"登记资料").append("）\n").append(s.content());
            }
            // messages.content is VARCHAR(4000); never trim away a source's safety conditions.
            if(text.codePointCount(0,text.length())>4000)return rejected();
            return new Answer("ANSWERED",text.toString(),List.copyOf(selected));
        } catch(RuntimeException invalid){return rejected();}
    }
}

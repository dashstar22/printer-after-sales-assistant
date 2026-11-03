package local.printer.knowledge;

import java.util.*;
import local.printer.administration.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Purpose and server configuration are separate grants. Legacy coarse consent grants nothing here. */
@Component
public class KnowledgePurposePolicy {
    private final Set<String> configured;
    public KnowledgePurposePolicy(Environment env) {
        configured=new HashSet<>();
        for(String key:env.getProperty("app.knowledge.processing-configurations","").split(","))if(!key.isBlank())configured.add(key.strip());
    }
    public JsonNode validate(JsonNode permissions,tools.jackson.databind.ObjectMapper json) {
        var result=json.createObjectNode();
        if(permissions!=null && (!permissions.isObject() || !Set.of("embedding","rerank","chat").containsAll(permissions.propertyNames())))throw AdminFailure.invalid("用途许可字段不正确");
        for(String purpose:List.of("embedding","rerank","chat")) {
            JsonNode input=permissions==null?null:permissions.get(purpose);
            var grant=result.putObject(purpose);
            if(input==null){grant.put("allowed",false);continue;}
            if(!input.isObject() || !Set.of("allowed","configurationId").containsAll(input.propertyNames()))throw AdminFailure.invalid("用途许可字段不正确");
            boolean allowed=AdminInput.bool(input,"allowed");grant.put("allowed",allowed);
            if(allowed) {
                String id=AdminInput.text(input,"configurationId",100);
                if(!configured.contains(purpose+":"+id))throw AdminFailure.invalid("未登记的用途服务配置");
                grant.put("configurationId",id);
            }
        }
        return result;
    }
    public void require(JsonNode permissions,String purpose,String configuration) {
        var grant=permissions.get(purpose);
        if(grant==null || !grant.path("allowed").asBoolean() || !grant.path("configurationId").asString().equals(configuration)
            || !configured.contains(purpose+":"+configuration))throw new AdminFailure(403,"PURPOSE_NOT_ALLOWED","该用途或服务配置尚未许可");
    }
    /** Only public configuration identities, never endpoints, credentials or grant defaults. */
    public Map<String,List<String>> availableConfigurations() {
        var result=new LinkedHashMap<String,List<String>>();
        for(String purpose:List.of("embedding","rerank","chat"))
            result.put(purpose,configured.stream().filter(k->k.startsWith(purpose+":"))
                .map(k->k.substring(purpose.length()+1)).sorted().toList());
        return result;
    }
}

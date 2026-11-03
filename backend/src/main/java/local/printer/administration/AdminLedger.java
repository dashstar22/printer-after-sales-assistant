package local.printer.administration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import local.printer.auth.SessionUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Small, serialized administrative write boundary. No secret input is persisted in audit. */
@Service
public class AdminLedger {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final BCryptPasswordEncoder hashes=new BCryptPasswordEncoder();
    public AdminLedger(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public JdbcTemplate db(){return db;}
    public ObjectMapper json(){return json;}
    public void lock(){db.queryForObject("SELECT id FROM administration_guard WHERE id=1 FOR UPDATE",Integer.class);}
    public void actor(SessionUser user, boolean admin) {
        var rows=db.queryForList("SELECT role,status,credential_version,must_change_password,email_verified_at,recovery_until,COALESCE(recovery_until>CURRENT_TIMESTAMP(6),FALSE) AS recovery_active FROM users WHERE id=?",user.identity().id());
        if(rows.isEmpty())throw new AccessDeniedException("身份失效");
        var row=rows.get(0);
        if(!"ACTIVE".equals(row.get("status")) || ((Number)row.get("credential_version")).longValue()!=user.credentialVersion()
            || (admin && (!"ADMIN".equals(row.get("role")) || Boolean.TRUE.equals(row.get("must_change_password")) || row.get("email_verified_at")==null)))
            throw new AccessDeniedException("身份失效或没有管理权限");
        if(!admin && row.get("email_verified_at")==null && (!user.passwordAuthenticated()
            || (row.get("recovery_until")!=null && !Boolean.TRUE.equals(row.get("recovery_active")) && !(row.get("recovery_active") instanceof Number n && n.intValue()!=0))))
            throw new AccessDeniedException("迁移凭证需要当前密码且必须在有效期内");
    }
    public JsonNode once(SessionUser user,String action,String object,JsonNode body,Supplier<JsonNode> mutation) {
        lock(); actor(user,!action.equals("PASSWORD_CHANGE"));
        String request=AdminInput.uuid(AdminInput.text(body,"requestId",36));
        String fingerprint=sha(canonical(body));
        var old=db.queryForList("SELECT object_id,request_hash,response_json FROM administration_audit WHERE actor_id=? AND action=? AND request_id=?",
            user.identity().id(),action,request);
        if(!old.isEmpty()) {
            var row=old.get(0);
            if((!object.equals("new") && !object.equals(row.get("object_id"))) || !hashes.matches(fingerprint,(String)row.get("request_hash")))
                throw AdminFailure.conflict("IDEMPOTENCY_CONFLICT");
            return json.readTree((String)row.get("response_json"));
        }
        JsonNode result=mutation.get();
        String actualObject=object;
        if(object.equals("new")) {
            if(result.has("id"))actualObject=Long.toString(result.get("id").asLong());
            else if(result.has("importId"))actualObject=result.get("importId").asString();
        }
        db.update("INSERT INTO administration_audit(actor_id,action,object_id,result,request_id,request_hash,response_json) VALUES(?,?,?,'SUCCESS',?,?,?)",
            user.identity().id(),action,actualObject,request,hashes.encode(fingerprint),json.writeValueAsString(result));
        return result;
    }
    private Object canonical(JsonNode node) {
        if(node.isObject()){var map=new TreeMap<String,Object>(); for(String key:node.propertyNames())map.put(key,canonical(node.get(key)));return map;}
        if(node.isArray()){var list=new ArrayList<Object>();node.forEach(n->list.add(canonical(n)));return list;}
        return json.convertValue(node,Object.class);
    }
    public String sha(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            (value instanceof String s?s:json.writeValueAsString(value)).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public JsonNode node(Object value){return json.valueToTree(value);}
    public long inserted(){return db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
    public Map<String,Object> row(String sql,Object... args){
        var rows=db.queryForList(sql,args); if(rows.isEmpty())throw new AdminFailure(404,"NOT_FOUND","未找到记录"); return rows.get(0);
    }
    public void version(Map<String,Object> row,JsonNode body) {
        if(((Number)row.get("version")).longValue()!=AdminInput.number(body,"expectedVersion"))throw AdminFailure.conflict("VERSION_CONFLICT");
    }
    public JsonNode page(String select,long cursor,int size) {
        if(cursor<0 || size<1 || size>100)throw AdminFailure.invalid("分页参数不正确");
        var rows=db.queryForList(select+" WHERE id>? ORDER BY id LIMIT ?",cursor,size+1);
        boolean more=rows.size()>size; if(more)rows.remove(rows.size()-1);
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("nextCursor",more?rows.get(rows.size()-1).get("id"):null);
        return node(result);
    }
    @Transactional
    public void rejected(long actor,String action,String code) {
        db.update("INSERT INTO administration_audit(actor_id,action,object_id,result,request_id,request_hash,response_json) VALUES(?,?,'-',?,?,?,'{}')",
            actor,"REJECTED_"+action,code,UUID.randomUUID().toString(),"-");
    }
}

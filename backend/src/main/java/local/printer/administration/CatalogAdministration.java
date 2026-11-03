package local.printer.administration;

import java.time.LocalDate;
import java.util.*;
import local.printer.auth.SessionUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class CatalogAdministration {
    final AdminLedger ledger;
    public CatalogAdministration(AdminLedger ledger){this.ledger=ledger;}
    public static final String MODELS="SELECT id,model_code AS modelCode,brand,is_demo AS isDemo,version FROM device_models";
    public static final String ORDERS="SELECT id,order_no AS orderNo,customer_id AS customerId,model_id AS modelId,serial_no AS serialNo,purchase_date AS purchaseDate,is_demo AS isDemo,status,void_reason AS voidReason,verified_by AS verifiedBy,verified_at AS verifiedAt,verification_note AS verificationNote,import_id AS importId,version FROM orders";
    @Transactional public JsonNode model(SessionUser actor,Long id,JsonNode b) {
        if(id==null)AdminInput.fields(b,"modelCode","brand","aliases","isDemo","requestId");
        else AdminInput.fields(b,"modelCode","brand","aliases","isDemo","requestId","expectedVersion");
        return ledger.once(actor,id==null?"MODEL_CREATE":"MODEL_UPDATE",id==null?"new":id.toString(),b,()->{
            if(id!=null){var old=ledger.row(MODELS+" WHERE id=? FOR UPDATE",id);ledger.version(old,b);
                // Keep referenced identities and legacy demo provenance. Unreferenced metadata remains editable.
                if(!old.get("isDemo").equals(AdminInput.bool(b,"isDemo")))throw AdminFailure.conflict("MODEL_PROVENANCE_IMMUTABLE");
                if(!old.get("modelCode").equals(AdminInput.text(b,"modelCode",64))
                    && (ledger.db().queryForObject("SELECT COUNT(*) FROM orders WHERE model_id=?",Long.class,id)>0
                    || ledger.db().queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE JSON_CONTAINS(models_json,JSON_QUOTE(?))",Long.class,old.get("modelCode"))>0))
                    throw AdminFailure.conflict("MODEL_REFERENCED");}
            var aliases=b.get("aliases");if(aliases==null || !aliases.isArray() || aliases.size()>50)throw AdminFailure.invalid("别名须为最多50项的数组");
            String code=AdminInput.text(b,"modelCode",64),brand=AdminInput.text(b,"brand",64);
            var keys=new LinkedHashMap<String,String>();keys.put(AdminInput.modelKey(code),code);
            for(var n:aliases){if(!n.isString())throw AdminFailure.invalid("别名必须为文本");String raw=AdminInput.text(n.asString(),"alias",64);
                if(keys.putIfAbsent(AdminInput.modelKey(raw),raw)!=null)throw AdminFailure.conflict("MODEL_ALIAS_CONFLICT");}
            // Includes every legacy canonical code; Java NFKC is deliberately not delegated to SQL collation.
            for(var row:ledger.db().queryForList("SELECT id,model_code FROM device_models"))
                if((id==null || ((Number)row.get("id")).longValue()!=id) && keys.containsKey(AdminInput.modelKey((String)row.get("model_code"))))
                    throw AdminFailure.conflict("MODEL_ALIAS_CONFLICT");
            for(String key:keys.keySet()) {
                var hits=ledger.db().queryForList("SELECT model_id FROM model_aliases WHERE normalized_alias=?",key);
                if(hits.stream().anyMatch(r->id==null || ((Number)r.get("model_id")).longValue()!=id))throw AdminFailure.conflict("MODEL_ALIAS_CONFLICT");
            }
            long target;
            if(id==null){ledger.db().update("INSERT INTO device_models(model_code,brand,is_demo) VALUES(?,?,?)",code,brand,AdminInput.bool(b,"isDemo"));target=ledger.inserted();}
            else {target=id;ledger.db().update("UPDATE device_models SET model_code=?,brand=?,version=version+1 WHERE id=? AND version=?",code,brand,id,AdminInput.number(b,"expectedVersion"));ledger.db().update("DELETE FROM model_aliases WHERE model_id=?",id);}
            keys.forEach((key,raw)->ledger.db().update("INSERT INTO model_aliases(normalized_alias,model_id,original_alias) VALUES(?,?,?)",key,target,raw));
            return modelDetail(target);
        });
    }
    public JsonNode modelDetail(long id) {
        var row=ledger.row(MODELS+" WHERE id=?",id);
        row.put("aliases",ledger.db().queryForList("SELECT original_alias AS originalAlias,normalized_alias AS normalizedAlias FROM model_aliases WHERE model_id=? ORDER BY normalized_alias",id));
        return ledger.node(row);
    }
    public long resolve(String raw) {
        String key=AdminInput.modelKey(raw);var matches=new HashSet<Long>();
        for(var row:ledger.db().queryForList("SELECT id,model_code FROM device_models"))if(AdminInput.modelKey((String)row.get("model_code")).equals(key))matches.add(((Number)row.get("id")).longValue());
        for(var row:ledger.db().queryForList("SELECT model_id FROM model_aliases WHERE normalized_alias=?",key))matches.add(((Number)row.get("model_id")).longValue());
        if(matches.size()!=1)throw AdminFailure.invalid("型号未知或有歧义，须登记审核别名");return matches.iterator().next();
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public JsonNode order(SessionUser actor,Long id,JsonNode b) {
        if(id==null)AdminInput.fields(b,"orderNo","customerId","modelId","serialNo","purchaseDate","isDemo","verificationNote","requestId");
        else AdminInput.fields(b,"orderNo","customerId","modelId","serialNo","purchaseDate","isDemo","verificationNote","requestId","expectedVersion");
        return ledger.once(actor,id==null?"ORDER_CREATE":"ORDER_UPDATE",id==null?"new":id.toString(),b,()->{
            validateOrder(b);
            if(id==null)return orderDetail(insertOrder(actor.identity().id(),b,null));
            var old=ledger.row(ORDERS+" WHERE id=? FOR UPDATE",id);ledger.version(old,b);
            if(!"ACTIVE".equals(old.get("status")))throw AdminFailure.conflict("ORDER_VOID");
            if(referenced(id))throw AdminFailure.conflict("ORDER_REFERENCED");
            // Provenance cannot be relabelled when correcting a record.
            if(!old.get("isDemo").equals(AdminInput.bool(b,"isDemo")))throw AdminFailure.conflict("ORDER_PROVENANCE_IMMUTABLE");
            ledger.db().update("UPDATE orders SET order_no=?,customer_id=?,model_id=?,serial_no=?,purchase_date=?,verification_note=?,verified_by=?,verified_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=? AND version=?",
                AdminInput.text(b,"orderNo",64),AdminInput.number(b,"customerId"),AdminInput.number(b,"modelId"),AdminInput.text(b,"serialNo",128),AdminInput.text(b,"purchaseDate",10),AdminInput.text(b,"verificationNote",500),actor.identity().id(),id,AdminInput.number(b,"expectedVersion"));
            return orderDetail(id);
        });
    }
    // Write lock prevents a referenced-order race: all new references lock the order through their FK.
    boolean referenced(long id){return ledger.db().queryForObject("SELECT (EXISTS(SELECT 1 FROM conversations WHERE order_id=?) OR EXISTS(SELECT 1 FROM repair_drafts WHERE order_id=?) OR EXISTS(SELECT 1 FROM tickets WHERE order_id=?))",Boolean.class,id,id,id);}
    void validateOrder(JsonNode b) {
        long customer=AdminInput.number(b,"customerId"),model=AdminInput.number(b,"modelId");
        if(ledger.db().queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND role='CUSTOMER' AND status='ACTIVE'",Long.class,customer)!=1)throw AdminFailure.invalid("客户不存在或已停用");
        if(ledger.db().queryForObject("SELECT COUNT(*) FROM device_models WHERE id=?",Long.class,model)!=1)throw AdminFailure.invalid("型号不存在");
        AdminInput.text(b,"orderNo",64);AdminInput.text(b,"serialNo",128);AdminInput.text(b,"verificationNote",500);AdminInput.bool(b,"isDemo");
        try {String date=AdminInput.text(b,"purchaseDate",10);LocalDate parsed=LocalDate.parse(date);
            if(!parsed.toString().equals(date) || parsed.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))) || parsed.isBefore(LocalDate.of(1900,1,1)))throw new IllegalArgumentException();}
        catch(RuntimeException e){throw AdminFailure.invalid("购买日期须为1900年至今日的YYYY-MM-DD日期");}
    }
    long insertOrder(long actor,JsonNode b,String batch) {
        ledger.db().update("INSERT INTO orders(order_no,customer_id,model_id,serial_no,purchase_date,is_demo,verified_by,verified_at,verification_note,import_id) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),?,?)",
            AdminInput.text(b,"orderNo",64),AdminInput.number(b,"customerId"),AdminInput.number(b,"modelId"),AdminInput.text(b,"serialNo",128),AdminInput.text(b,"purchaseDate",10),AdminInput.bool(b,"isDemo"),actor,AdminInput.text(b,"verificationNote",500),batch);
        return ledger.inserted();
    }
    public JsonNode orderDetail(long id){return ledger.node(ledger.row(ORDERS+" WHERE id=?",id));}
    @Transactional public JsonNode voidOrder(SessionUser actor,long id,JsonNode b) {
        AdminInput.fields(b,"reason","expectedVersion","requestId");
        return ledger.once(actor,"ORDER_VOID",Long.toString(id),b,()->{
            var old=ledger.row(ORDERS+" WHERE id=? FOR UPDATE",id);ledger.version(old,b);
            if(!"ACTIVE".equals(old.get("status")))throw AdminFailure.conflict("ORDER_VOID");
            ledger.db().update("UPDATE orders SET status='VOID',void_reason=?,version=version+1 WHERE id=? AND version=?",AdminInput.text(b,"reason",500),id,AdminInput.number(b,"expectedVersion"));
            return orderDetail(id);
        });
    }
}

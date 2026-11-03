package local.printer.administration;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;
import local.printer.auth.SessionUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OrderImportService {
    public static final String HEADER="orderNo,customerId,modelCode,serialNo,purchaseDate,isDemo,verificationNote";
    private final AdminLedger ledger;
    private final CatalogAdministration catalog;
    public OrderImportService(AdminLedger ledger,CatalogAdministration catalog){this.ledger=ledger;this.catalog=catalog;}
    @Transactional public JsonNode preview(SessionUser actor,String requestId,byte[] input) {
        if(input.length>1024*1024)throw new AdminFailure(413,"RESOURCE_LIMIT","CSV不能超过1MB");
        String hash;
        try {hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        var identity=ledger.json().createObjectNode().put("requestId",requestId).put("inputHash",hash);
        return ledger.once(actor,"IMPORT_PREVIEW","new",identity,()->{
            String text;
            try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();}
            catch(CharacterCodingException e){throw AdminFailure.invalid("CSV须为UTF-8编码");}
            if(text.startsWith("\uFEFF"))text=text.substring(1);
            var rows=new ArrayList<Object>();var errors=new ArrayList<Object>();var names=new ArrayList<String>();
            try(var parser=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(false).get().parse(new StringReader(text))) {
                if(!parser.getHeaderNames().equals(List.of(HEADER.split(","))))throw AdminFailure.invalid("CSV列名或顺序与模板不符");
                for(CSVRecord record:parser) {
                    long line=record.getRecordNumber()+1;
                    if(line>501)throw new AdminFailure(413,"RESOURCE_LIMIT","CSV不能超过500行");
                    try {
                        if(record.size()!=7)throw AdminFailure.invalid("字段数量不符");
                        long customer;
                        try{if(!record.get(1).matches("[1-9][0-9]*"))throw new NumberFormatException();customer=Long.parseLong(record.get(1));}
                        catch(NumberFormatException e){throw AdminFailure.invalid("customerId须为客户内部ID");}
                        if(!Set.of("true","false").contains(record.get(5)))throw AdminFailure.invalid("isDemo须为true或false");
                        var b=ledger.json().createObjectNode().put("orderNo",record.get(0)).put("customerId",customer)
                            .put("modelId",catalog.resolve(record.get(2))).put("modelInput",record.get(2)).put("serialNo",record.get(3))
                            .put("purchaseDate",record.get(4)).put("isDemo",Boolean.parseBoolean(record.get(5))).put("verificationNote",record.get(6));
                        catalog.validateOrder(b);
                        // MySQL's legacy case/accent-insensitive order unique key is authoritative at commit.
                        String order=AdminInput.text(b,"orderNo",64);
                        boolean duplicate=ledger.db().queryForObject("SELECT EXISTS(SELECT 1 FROM JSON_TABLE(?, '$[*]' COLUMNS (value VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci PATH '$')) j WHERE j.value=?)",Boolean.class,ledger.json().writeValueAsString(names),order);
                        names.add(order);
                        if(duplicate || ledger.db().queryForObject("SELECT COUNT(*) FROM orders WHERE order_no=?",Long.class,order)>0)
                            throw AdminFailure.invalid("订单编号重复");
                        rows.add(b);
                    }catch(AdminFailure e){errors.add(Map.of("row",line,"code",e.code,"message",e.getMessage()));}
                }
            }catch(IOException|UncheckedIOException|IllegalArgumentException e){throw AdminFailure.invalid("CSV格式错误，请核对引号和字段");}
            if(rows.isEmpty() && errors.isEmpty())throw AdminFailure.invalid("CSV没有数据行");
            String id=UUID.randomUUID().toString();Instant expires=Instant.now().plusSeconds(1800);
            ledger.db().update("INSERT INTO order_imports(id,actor_id,input_hash,snapshot_json,errors_json,expires_at,status) VALUES(?,?,?,?,?,?,'PREVIEW')",
                id,actor.identity().id(),hash,ledger.json().writeValueAsString(rows),ledger.json().writeValueAsString(errors),java.sql.Timestamp.from(expires));
            return detail(actor,id);
        });
    }
    public JsonNode detail(SessionUser actor,String id) {
        var r=ledger.row("SELECT id,input_hash,expires_at,status,version,snapshot_json,errors_json,result_json FROM order_imports WHERE id=? AND actor_id=?",id,actor.identity().id());
        var result=new LinkedHashMap<String,Object>();
        result.put("importId",r.get("id"));result.put("inputHash",r.get("input_hash"));result.put("expiresAt",((java.sql.Timestamp)r.get("expires_at")).toInstant());
        result.put("status",r.get("status"));result.put("version",r.get("version"));result.put("rows",ledger.json().readTree((String)r.get("snapshot_json")));
        result.put("errors",ledger.json().readTree((String)r.get("errors_json")));result.put("result",r.get("result_json")==null?null:ledger.json().readTree((String)r.get("result_json")));
        return ledger.node(result);
    }
    @Transactional public JsonNode commit(SessionUser actor,String id,JsonNode b) {
        AdminInput.fields(b,"inputHash","expectedVersion","requestId");
        return ledger.once(actor,"IMPORT_COMMIT",id,b,()->{
            var row=ledger.row("SELECT * FROM order_imports WHERE id=? AND actor_id=? FOR UPDATE",id,actor.identity().id());
            if(!row.get("input_hash").equals(AdminInput.text(b,"inputHash",64)))throw AdminFailure.conflict("PREVIEW_STALE");
            if("COMMITTED".equals(row.get("status")))return ledger.json().readTree((String)row.get("result_json"));
            ledger.version(row,b);
            if(!((java.sql.Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now()) || !ledger.json().readTree((String)row.get("errors_json")).isEmpty())
                throw AdminFailure.conflict("PREVIEW_STALE");
            var ids=new ArrayList<Long>();
            for(JsonNode input:ledger.json().readTree((String)row.get("snapshot_json"))) {
                catalog.validateOrder(input);
                if(catalog.resolve(AdminInput.text(input,"modelInput",64))!=AdminInput.number(input,"modelId"))throw AdminFailure.conflict("PREVIEW_STALE");
                ids.add(catalog.insertOrder(actor.identity().id(),input,id));
            }
            var result=ledger.node(Map.of("importId",id,"status","COMMITTED","version",1,"orderIds",ids,"count",ids.size()));
            ledger.db().update("UPDATE order_imports SET status='COMMITTED',version=version+1,result_json=? WHERE id=? AND version=?",ledger.json().writeValueAsString(result),id,AdminInput.number(b,"expectedVersion"));
            return result;
        });
    }
}

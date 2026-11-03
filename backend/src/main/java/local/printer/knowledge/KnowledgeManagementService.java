package local.printer.knowledge;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import local.printer.administration.*;
import local.printer.auth.SessionUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Shared application boundary for managed revisions. No model, vector or broker dependency. */
@Service
public class KnowledgeManagementService {
    private final AdminLedger ledger;private final KnowledgeApplicationService application;
    private final KnowledgePrivateStorage storage;private final KnowledgePurposePolicy purposes;private final TransactionTemplate tx;private final CatalogAdministration catalog;
    private final local.printer.jobs.KnowledgeJobStore jobs;
    private final local.printer.knowledge.index.KnowledgeIndexPipeline indexes;
    private final java.util.concurrent.Semaphore preprocessing=new java.util.concurrent.Semaphore(1);
    public KnowledgeManagementService(AdminLedger ledger,KnowledgeApplicationService application,KnowledgePrivateStorage storage,
            KnowledgePurposePolicy purposes,PlatformTransactionManager manager,CatalogAdministration catalog,local.printer.jobs.KnowledgeJobStore jobs,local.printer.knowledge.index.KnowledgeIndexPipeline indexes) {
        this.ledger=ledger;this.application=application;this.storage=storage;this.purposes=purposes;this.catalog=catalog;this.jobs=jobs;this.indexes=indexes;tx=new TransactionTemplate(manager);
    }
    public JsonNode upload(SessionUser actor,Long id,JsonNode input,String filename,byte[] bytes) {
        ledger.actor(actor,true);
        var metadata=metadata(input,id!=null);
        String name=AdminInput.text(filename,"fileName",200);
        if(name.contains("/") || name.contains("\\") || name.contains(":") || name.equals(".") || name.equals(".."))throw AdminFailure.invalid("文件名不能包含路径");
        int dot=name.lastIndexOf('.');String extension=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
        int size=metadata.get("chunkSize").asInt(),overlap=metadata.get("chunkOverlap").asInt();
        KnowledgePreprocessor.Result processed;
        if(!preprocessing.tryAcquire())throw new AdminFailure(429,"PREPROCESSING_BUSY","正在处理另一份资料，请稍后重试");
        try {processed=KnowledgePreprocessor.process(bytes,extension,size,overlap);}finally{preprocessing.release();}
        String content=KnowledgeIdentity.sha(bytes),config=KnowledgeIdentity.digest(KnowledgePreprocessor.LAYOUT,size,overlap,"codepoint-separators-v1");
        // Audit stores a fingerprint, not original bytes, FAQ body or extracted text.
        var identity=metadata.deepCopy();identity.put("contentHash",content);identity.put("fileName",name);
        String key=storage.save(bytes,extension);
        boolean committed=false;
        try {
            JsonNode result=tx.execute(s->ledger.once(actor,"KNOWLEDGE_UPLOAD",id==null?"new":id.toString(),identity,()->{
                // Recheck canonical model identities under the same guard as catalog maintenance.
                if(!metadata.equals(metadata(input,id!=null)))throw AdminFailure.conflict("PREVIEW_STALE");
                long documentId;int revision;
                if(id==null) {
                    var document=document(metadata,key);
                    documentId=application.register(List.of(document)).get(0);revision=1;
                    ledger.db().update("UPDATE knowledge_documents SET layout_version=?,storage_key=? WHERE id=?",KnowledgePreprocessor.LAYOUT,key,documentId);
                }else {
                    var old=ledger.row("SELECT document_key,version_lock,layout_version,revision FROM knowledge_documents WHERE id=? FOR UPDATE",id);
                    if(((Number)old.get("version_lock")).longValue()!=AdminInput.number(input,"expectedVersion"))throw AdminFailure.conflict("VERSION_CONFLICT");
                    if(!metadata.get("documentKey").asString().equals(old.get("document_key")) || !KnowledgePreprocessor.LAYOUT.equals(old.get("layout_version")))throw AdminFailure.conflict("LEGACY_LAYOUT_IMMUTABLE");
                    documentId=id;revision=((Number)old.get("revision")).intValue()+1;
                    ledger.db().update("UPDATE knowledge_documents SET revision=?,version_lock=version_lock+1 WHERE id=?",revision,id);
                }
                String binding=KnowledgeIdentity.digest(documentId,revision,content,config,metadata.toString());
                var layoutValues=new ArrayList<Object>(List.of(KnowledgePreprocessor.LAYOUT,binding));
                for(var c:processed.chunks()){layoutValues.add(c.text());layoutValues.add(c.page());layoutValues.add(c.section());layoutValues.add(c.start());layoutValues.add(c.end());}
                String layout=KnowledgeIdentity.digest(layoutValues.toArray());
                ledger.db().update("""
                    INSERT INTO knowledge_revisions(document_id,revision,storage_key,file_name,metadata_json,content_hash,layout_hash,
                      config_hash,binding_hash,layout_version,extracted_text,chunk_count,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,documentId,revision,key,name,metadata.toString(),content,layout,config,binding,KnowledgePreprocessor.LAYOUT,processed.text(),processed.chunks().size(),actor.identity().id());
                int index=0;
                for(var c:processed.chunks()) {
                    String stable=KnowledgeIdentity.digest(documentId,revision,layout,index,c.start(),c.end(),c.text());
                    ledger.db().update("""
                        INSERT INTO knowledge_chunks(document_id,revision,chunk_index,content,page_number,page_end,section,offset_start,offset_end,stable_chunk_id,layout_version)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?)
                        """,documentId,revision,index++,c.text(),c.page(),c.pageEnd(),c.section(),c.start(),c.end(),stable,KnowledgePreprocessor.LAYOUT);
                }
                for(String p:List.of("embedding","rerank","chat")) {
                    var grant=metadata.get("processingPermissions").get(p);
                    ledger.db().update("INSERT INTO knowledge_permissions(document_id,revision,purpose,allowed,configuration_id) VALUES(?,?,?,?,?)",
                        documentId,revision,p,grant.get("allowed").asBoolean(),grant.has("configurationId")?grant.get("configurationId").asString():null);
                }
                var response=(ObjectNode)detail(documentId);response.put("emptyPages",processed.emptyPages());response.put("units",processed.units());return response;
            }));
            committed=true;
            // A request replay reused an existing immutable original. Delete only our unused new file.
            if(ledger.db().queryForObject("SELECT COUNT(*) FROM knowledge_revisions WHERE storage_key=?",Integer.class,key)==0)storage.discardNew(key);
            return result;
        }catch(RuntimeException e){if(!committed)storage.discardNew(key);throw e;}
    }
    public JsonNode faq(SessionUser actor,Long id,JsonNode body) {
        if(body==null || !body.isObject())throw AdminFailure.invalid("FAQ须为JSON对象");
        var fields=new HashSet<>(body.propertyNames());
        if(!fields.remove("text"))throw AdminFailure.invalid("FAQ缺少text");
        var metadata=(ObjectNode)body.deepCopy();metadata.remove("text");
        var text=body.get("text");
        if(text==null || !text.isString() || text.asString().isBlank() || text.asString().codePointCount(0,text.asString().length())>KnowledgeTextExtractor.MAX_TEXT
            || text.asString().codePoints().anyMatch(c->c==0 || c>=0xD800 && c<=0xDFFF))throw AdminFailure.invalid("FAQ正文为空、编码无效或超过200万字符");
        if(!Set.of("enterprise_faq","self_authored_simulation").contains(metadata.path("sourceType").asString()))throw AdminFailure.invalid("FAQ须使用企业FAQ或模拟来源");
        return upload(actor,id,metadata,"faq.md",text.asString().getBytes(StandardCharsets.UTF_8));
    }
    private ObjectNode metadata(JsonNode input,boolean revised) {
        var fields=new HashSet<>(Set.of("requestId","title","documentKey","version","sourceType","modelScope","models","source","useCondition","distributionAllowed","processingPermissions","isSimulated","chunkSize","chunkOverlap"));
        if(revised)fields.add("expectedVersion");
        if(input==null || !input.isObject() || !fields.containsAll(input.propertyNames()))throw AdminFailure.invalid("元数据包含未允许字段");
        // Missing optional permissions and chunk configuration are fail-closed/defaulted.
        var result=ledger.json().createObjectNode();
        result.put("requestId",AdminInput.uuid(AdminInput.text(input,"requestId",36)));
        result.put("title",AdminInput.text(input,"title",200));
        String documentKey=AdminInput.text(input,"documentKey",96);
        if(!documentKey.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"))throw AdminFailure.invalid("documentKey格式不正确");
        result.put("documentKey",documentKey);result.put("version",AdminInput.text(input,"version",64));
        String source=AdminInput.text(input,"sourceType",32);
        if(!Set.of("official_manual","enterprise_policy","enterprise_faq","self_authored_simulation").contains(source))throw AdminFailure.invalid("来源类型不正确");
        boolean simulated=AdminInput.bool(input,"isSimulated");
        if(simulated!=source.equals("self_authored_simulation"))throw AdminFailure.invalid("来源类型与模拟标记不符");
        result.put("sourceType",source);result.put("isSimulated",simulated);
        String scope=AdminInput.text(input,"modelScope",16);var models=input.get("models");
        if(!Set.of("MODELS","GENERAL").contains(scope) || models==null || !models.isArray() || models.size()>20 ||
            scope.equals("GENERAL") && (!models.isEmpty() || source.equals("official_manual")) || scope.equals("MODELS") && models.isEmpty())throw AdminFailure.invalid("适用型号范围不正确");
        var canonical=new TreeSet<String>();
        for(var model:models) {
            if(!model.isString())throw AdminFailure.invalid("型号须为文本");
            long modelId=catalog.resolve(model.asString());
            String code=ledger.db().queryForObject("SELECT model_code FROM device_models WHERE id=?",String.class,modelId);
            if(!canonical.add(code))throw AdminFailure.invalid("型号重复");
        }
        result.put("modelScope",scope);result.set("models",ledger.node(canonical));
        result.put("useCondition",AdminInput.text(input,"useCondition",2000));
        result.put("distributionAllowed",AdminInput.bool(input,"distributionAllowed"));
        result.set("processingPermissions",purposes.validate(input.get("processingPermissions"),ledger.json()));
        long rawSize=input.has("chunkSize")?AdminInput.number(input,"chunkSize"):500;
        long rawOverlap=input.has("chunkOverlap")?AdminInput.number(input,"chunkOverlap"):50;
        if(rawSize>600 || rawOverlap>100)throw AdminFailure.invalid("切分配置超限");
        int size=(int)rawSize,overlap=(int)rawOverlap;
        KnowledgePreprocessor.parameters(size,overlap);result.put("chunkSize",size);result.put("chunkOverlap",overlap);
        var provenance=input.get("source");
        if(source.equals("official_manual")) {
            AdminInput.fields(provenance,"description","checkedOn","downloadUrl","officialIndex","termsUrl","brand","region","language","acquiredOn","coverModels");
            for(String url:List.of("downloadUrl","officialIndex","termsUrl"))url(AdminInput.text(provenance,url,2048));
            for(String field:List.of("brand","region","language"))AdminInput.text(provenance,field,64);
            date(AdminInput.text(provenance,"acquiredOn",10));
            var cover=provenance.get("coverModels");if(cover==null || !cover.isArray() || cover.size()>100)throw AdminFailure.invalid("官方覆盖型号缺失");
            var covered=new HashSet<String>();for(var c:cover){if(!c.isString())throw AdminFailure.invalid("覆盖型号须为文本");covered.add(AdminInput.modelKey(c.asString()));}
            for(String c:canonical)if(!covered.contains(AdminInput.modelKey(c)))throw AdminFailure.invalid("所选型号不在官方覆盖清单");
        }else AdminInput.fields(provenance,"description","checkedOn");
        AdminInput.text(provenance,"description",2000);date(AdminInput.text(provenance,"checkedOn",10));
        result.set("source",provenance);
        if(revised)result.put("expectedVersion",AdminInput.number(input,"expectedVersion"));
        return result;
    }
    private static LocalDate date(String value) {
        try {var date=LocalDate.parse(value);if(date.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))throw new IllegalArgumentException();return date;}
        catch(Exception e){throw AdminFailure.invalid("来源核查日期无效或在未来");}
    }
    private static void url(String value) {
        try {var uri=java.net.URI.create(value);if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)throw new IllegalArgumentException();}
        catch(Exception e){throw AdminFailure.invalid("官方来源须提供HTTPS地址");}
    }
    private KnowledgeRows.Document document(JsonNode m,String key) {
        var source=m.get("source");boolean official=m.get("sourceType").asString().equals("official_manual");
        return new KnowledgeRows.Document(null,m.get("documentKey").asString(),m.get("version").asString(),m.get("title").asString(),m.get("sourceType").asString(),
            !official,m.get("isSimulated").asBoolean(),m.get("modelScope").asString(),m.get("models").toString(),
            official?source.get("downloadUrl").asString():null,official?source.get("officialIndex").asString():null,official?source.get("termsUrl").asString():null,
            official?source.get("brand").asString():null,official?source.get("region").asString():null,official?source.get("language").asString():null,
            official?date(source.get("acquiredOn").asString()):null,date(source.get("checkedOn").asString()),m.get("useCondition").asString(),
            m.get("distributionAllowed").asBoolean(),false,key,source.toString(),null,"PENDING",false);
    }
    private Map<String,Object> current(long id) {
        return ledger.row("""
            SELECT r.*,d.version_lock AS version,d.revision AS current_revision FROM knowledge_revisions r
            JOIN knowledge_documents d ON d.id=r.document_id AND d.revision=r.revision WHERE d.id=?
            """,id);
    }
    public JsonNode detail(long id) {
        var r=current(id);var result=ledger.json().createObjectNode();
        result.put("id",id);result.put("revision",((Number)r.get("revision")).intValue());result.put("version",((Number)r.get("version")).longValue());
        var doc=ledger.row("SELECT status,active FROM knowledge_documents WHERE id=?",id);
        var active=ledger.db().queryForList("SELECT g.revision,g.generation_id FROM knowledge_active_generations a JOIN knowledge_index_generations g ON g.generation_id=a.generation_id WHERE g.document_id=?",id);
        boolean revisionActive=Boolean.TRUE.equals(doc.get("active")) && !active.isEmpty() && ((Number)active.get(0).get("revision")).intValue()==((Number)r.get("revision")).intValue();
        result.put("documentStatus",(String)doc.get("status"));result.put("documentActive",Boolean.TRUE.equals(doc.get("active")));
        result.put("status",revisionActive?"READY":"PENDING");result.put("active",revisionActive);result.put("stage",(String)r.get("stage"));result.put("layoutVersion",(String)r.get("layout_version"));
        result.put("contentHash",(String)r.get("content_hash"));result.put("layoutHash",(String)r.get("layout_hash"));result.put("configHash",(String)r.get("config_hash"));
        result.put("chunkCount",((Number)r.get("chunk_count")).intValue());result.set("metadata",ledger.json().readTree((String)r.get("metadata_json")));
        result.set("publicationRequests",ledger.node(ledger.db().queryForList("SELECT id AS jobId,revision,status,created_at AS createdAt FROM knowledge_publication_requests WHERE document_id=? ORDER BY created_at",id)));
        result.put("knowledgeEpoch",indexes.epoch());result.set("indexGenerations",ledger.node(indexes.results(id)));result.set("indexBatches",ledger.node(indexes.batches(id)));
        result.set("activeGeneration",active.isEmpty()?ledger.json().nullNode():ledger.node(active.get(0)));
        return result;
    }
    public JsonNode list(SessionUser actor,long cursor,int size) {
        ledger.actor(actor,true);
        return ledger.page("""
            SELECT id,document_key AS documentKey,title,version,revision,source_type AS sourceType,
                is_simulated AS isSimulated,model_scope AS modelScope,models_json AS modelsJson,
                status AS documentStatus,active AS documentActive,layout_version AS layoutVersion
            FROM knowledge_documents
            """,cursor,size);
    }
    public JsonNode options(SessionUser actor) {
        ledger.actor(actor,true);return ledger.node(Map.of("processingConfigurations",purposes.availableConfigurations()));
    }
    public JsonNode disable(SessionUser actor,long id,JsonNode body) {
        AdminInput.fields(body,"requestId","expectedVersion","revision");
        return tx.execute(s->ledger.once(actor,"KNOWLEDGE_DISABLE",Long.toString(id),body,()->{
            var current=current(id);
            if(AdminInput.number(body,"expectedVersion")!=((Number)current.get("version")).longValue())throw AdminFailure.conflict("VERSION_CONFLICT");
            long requested=AdminInput.number(body,"revision");
            if(requested<1 || requested>Integer.MAX_VALUE)throw AdminFailure.invalid("版本号不正确");
            if(ledger.db().queryForObject("SELECT COUNT(*) FROM knowledge_revisions WHERE document_id=? AND revision=?",Integer.class,id,requested)!=1)throw new AdminFailure(404,"NOT_FOUND","未找到资料版本");
            indexes.disable(id,(int)requested);
            return detail(id);
        }));
    }
    public JsonNode preview(SessionUser actor,long id,int revision,int cursor,int size,String priorToken) {
        if(cursor<0 || size<1 || size>100)throw AdminFailure.invalid("分页范围不正确");
        return tx.execute(s->{
            ledger.lock();ledger.actor(actor,true);var r=current(id);
            if(revision!=((Number)r.get("revision")).intValue())throw AdminFailure.conflict("PREVIEW_STALE");
            if(cursor>=((Number)r.get("chunk_count")).intValue())throw AdminFailure.invalid("分页游标超出范围");
            String token;
            if(cursor==0) {
                token=UUID.randomUUID().toString();
                ledger.db().update("INSERT INTO knowledge_preview_confirmations(token,document_id,revision,actor_id,binding_hash,expires_at) VALUES(?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 MINUTE))",
                    token,id,revision,actor.identity().id(),r.get("binding_hash"));
            }else {
                token=AdminInput.uuid(AdminInput.text(priorToken,"previewToken",36));
                int valid=ledger.db().queryForObject("SELECT COUNT(*) FROM knowledge_preview_confirmations WHERE token=? AND document_id=? AND revision=? AND actor_id=? AND binding_hash=? AND covered_until>=? AND expires_at>UTC_TIMESTAMP()",
                    Integer.class,token,id,revision,actor.identity().id(),r.get("binding_hash"),cursor);
                if(valid!=1)throw AdminFailure.conflict("PREVIEW_STALE");
            }
            var rows=ledger.db().queryForList("""
                SELECT chunk_index AS chunkIndex,stable_chunk_id AS chunkId,content,page_number AS pageStart,page_end AS pageEnd,
                  section AS sectionPath,offset_start AS offsetStart,offset_end AS offsetEnd
                FROM knowledge_chunks WHERE document_id=? AND revision=? AND chunk_index>=? ORDER BY chunk_index LIMIT ?
                """,id,revision,cursor,size+1);
            boolean more=rows.size()>size;if(more)rows.remove(rows.size()-1);
            var result=(ObjectNode)detail(id);result.set("items",ledger.node(rows));
            if(more)result.put("nextCursor",((Number)rows.get(rows.size()-1).get("chunkIndex")).intValue()+1);else result.putNull("nextCursor");
            int covered=cursor+rows.size();
            ledger.db().update("UPDATE knowledge_preview_confirmations SET covered_until=GREATEST(covered_until,?) WHERE token=?",covered,token);
            result.put("reviewComplete",covered==((Number)r.get("chunk_count")).intValue());
            result.put("previewToken",token);return result;
        });
    }
    public JsonNode publish(SessionUser actor,long id,JsonNode body) {
        AdminInput.fields(body,"requestId","expectedVersion","revision","previewToken","contentHash","layoutHash","configHash");
        return tx.execute(s->ledger.once(actor,"KNOWLEDGE_PUBLISH",Long.toString(id),body,()->{
            var r=current(id);int revision=((Number)r.get("revision")).intValue();
            if("DISABLED".equals(r.get("stage")))throw AdminFailure.conflict("REVISION_DISABLED");
            if(AdminInput.number(body,"expectedVersion")!=((Number)r.get("version")).longValue() || AdminInput.number(body,"revision")!=revision)throw AdminFailure.conflict("PREVIEW_STALE");
            for(String field:List.of("contentHash","layoutHash","configHash")) {
                String column=switch(field){case "contentHash"->"content_hash";case "layoutHash"->"layout_hash";default->"config_hash";};
                if(!AdminInput.text(body,field,64).equals(r.get(column)))throw AdminFailure.conflict("PREVIEW_STALE");
            }
            String token=AdminInput.uuid(AdminInput.text(body,"previewToken",36));
            int valid=ledger.db().queryForObject("""
                SELECT COUNT(*) FROM knowledge_preview_confirmations WHERE token=? AND document_id=? AND revision=? AND actor_id=?
                  AND binding_hash=? AND covered_until=? AND expires_at>UTC_TIMESTAMP()
                """,Integer.class,token,id,revision,actor.identity().id(),r.get("binding_hash"),r.get("chunk_count"));
            if(valid!=1)throw AdminFailure.conflict("PREVIEW_STALE");
            JsonNode metadata=ledger.json().readTree((String)r.get("metadata_json"));
            var grants=metadata.get("processingPermissions");
            for(String purpose:List.of("embedding","rerank","chat")) {
                purposes.require(grants,purpose,grants.path(purpose).path("configurationId").asString());
                int permitted=ledger.db().queryForObject("SELECT COUNT(*) FROM knowledge_permissions WHERE document_id=? AND revision=? AND purpose=? AND allowed=TRUE AND configuration_id=?",
                    Integer.class,id,revision,purpose,grants.get(purpose).get("configurationId").asString());
                if(permitted!=1)throw new AdminFailure(403,"PURPOSE_NOT_ALLOWED","当前用途许可不足");
            }
            if(!KnowledgeIdentity.sha(storage.read((String)r.get("storage_key"))).equals(r.get("content_hash")))throw AdminFailure.conflict("PREVIEW_STALE");
            var old=ledger.db().queryForList("SELECT id FROM knowledge_publication_requests WHERE document_id=? AND revision=? AND binding_hash=?",id,revision,r.get("binding_hash"));
            String job=old.isEmpty()?UUID.randomUUID().toString():(String)old.get(0).get("id");
            if(old.isEmpty())ledger.db().update("INSERT INTO knowledge_publication_requests(id,document_id,revision,binding_hash,actor_id) VALUES(?,?,?,?,?)",job,id,revision,r.get("binding_hash"),actor.identity().id());
            jobs.enqueue(job);
            var task=ledger.row("SELECT state,stage FROM knowledge_jobs WHERE id=?",job);
            if(old.isEmpty())ledger.db().update("UPDATE knowledge_revisions SET stage='PUBLISH_REQUESTED' WHERE document_id=? AND revision=?",id,revision);
            return ledger.node(Map.of("id",id,"revision",revision,"jobId",job,"status",task.get("state"),"stage",task.get("stage"),"active",detail(id).get("active").asBoolean()));
        }));
    }
    public byte[] original(long id,int revision) {
        var r=ledger.row("SELECT storage_key,metadata_json,content_hash FROM knowledge_revisions WHERE document_id=? AND revision=?",id,revision);
        if(!ledger.json().readTree((String)r.get("metadata_json")).get("distributionAllowed").asBoolean())throw new AdminFailure(403,"DISTRIBUTION_NOT_ALLOWED","未许可分发原件");
        byte[] bytes=storage.read((String)r.get("storage_key"));
        if(!KnowledgeIdentity.sha(bytes).equals(r.get("content_hash")))throw AdminFailure.conflict("SOURCE_CHANGED");return bytes;
    }
}

package local.printer.knowledge;

import java.net.URI;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import local.printer.knowledge.KnowledgeRows.Document;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Local metadata reader only. Does not extract document text or perform network I/O. */
final class KnowledgeManifest {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> COMMON = Set.of("id","title","source_type","self_authored","is_simulated",
        "model_scope","selected_models","version","use_condition","local_file","distribution_allowed","external_upload_allowed");
    private static final Set<String> OFFICIAL = Set.of("brand","cover_models","official_index","download_url",
        "region","language","acquired_on","terms_url","pdf_metadata_modified_at","publication_date","sha256",
        "pdf_pages","verified_locations","status");
    private static final Set<String> SELF = Set.of("source","official_manual","status");
    private KnowledgeManifest() {}

    static List<Document> read(Path rootInput, String manifest, List<String> ids) {
        try {
            Path root = rootInput.toRealPath();
            Path input = local(root, manifest, false);
            if (Files.size(input)>1048576) throw KnowledgeFailure.invalid("KNOWLEDGE_MANIFEST_INVALID");
            JsonNode all = JSON.readTree(Files.readString(input));
            if (all==null || !all.isObject() || !Set.of("checked_on","documents").containsAll(all.propertyNames()))
                throw KnowledgeFailure.invalid("KNOWLEDGE_MANIFEST_INVALID");
            LocalDate checked = date(all,"checked_on");
            JsonNode documents = all.get("documents");
            if (documents==null || !documents.isArray() || documents.isEmpty() || documents.size()>100)
                throw KnowledgeFailure.invalid("KNOWLEDGE_MANIFEST_INVALID");
            var byId = new HashMap<String,JsonNode>();
            for (JsonNode d: documents) {
                if (!d.isObject()) throw KnowledgeFailure.invalid("KNOWLEDGE_MANIFEST_INVALID");
                String id = key(text(d,"id",96));
                if (byId.put(id,d)!=null) throw KnowledgeFailure.invalid("KNOWLEDGE_MANIFEST_INVALID");
            }
            var result = new ArrayList<Document>();
            for (String id:ids) {
                JsonNode d=byId.get(id);
                if(d==null) throw KnowledgeFailure.invalid("KNOWLEDGE_DOCUMENT_NOT_FOUND");
                result.add(document(root,d,checked));
            }
            return result;
        } catch (KnowledgeFailure failure) { throw failure;
        } catch (Exception failure) { throw KnowledgeFailure.invalid("KNOWLEDGE_MANIFEST_INVALID"); }
    }

    private static Document document(Path root, JsonNode d, LocalDate checked) throws Exception {
        String type=text(d,"source_type",32);
        boolean official=type.equals("official_manual");
        if (!official && !type.equals("self_authored_simulation")) throw invalid();
        var allowed=new HashSet<>(COMMON);allowed.addAll(official?OFFICIAL:SELF);
        if(!allowed.containsAll(d.propertyNames())) throw invalid();
        if(d.has("status") && (!d.get("status").isString() || d.get("status").asString().equals("PROTOTYPE_ONLY"))) throw invalid();
        boolean authored=bool(d,"self_authored"), simulated=bool(d,"is_simulated");
        if(authored==official || simulated==official) throw invalid();
        String scope=text(d,"model_scope",16);
        List<String> models=models(d,"selected_models",scope.equals("GENERAL"));
        if(!(scope.equals("MODELS") || scope.equals("GENERAL")) || (scope.equals("GENERAL") && (official || !models.isEmpty()))
            || (scope.equals("MODELS") && models.isEmpty())) throw invalid();
        String url=null,index=null,terms=null,brand=null,region=null,language=null;
        LocalDate acquired=null;
        if(official) {
            if(models.stream().anyMatch(m->m.equalsIgnoreCase("P210") || m.equalsIgnoreCase("P310"))) throw invalid();
            if(!models(d,"cover_models",false).containsAll(models)) throw invalid();
            url=url(d,"download_url");index=url(d,"official_index");terms=url(d,"terms_url");
            brand=text(d,"brand",64);region=text(d,"region",64);language=text(d,"language",32);acquired=date(d,"acquired_on");
        } else if(bool(d,"official_manual") || !text(d,"source",64).equals("自编模拟企业规则")) throw invalid();
        String local=text(d,"local_file",1024);
        Path resolved=local(root,local,true);
        local=root.relativize(resolved).toString().replace('\\','/');
        ObjectNode provenance=(ObjectNode)d.deepCopy();provenance.put("checked_on",checked.toString());
        return new Document(null,key(text(d,"id",96)),text(d,"version",64),text(d,"title",200),type,authored,simulated,
            scope,JSON.writeValueAsString(models),url,index,terms,brand,region,language,acquired,checked,text(d,"use_condition",2000),
            bool(d,"distribution_allowed"),bool(d,"external_upload_allowed"),local,JSON.writeValueAsString(provenance),null,"PENDING",false);
    }

    static Path local(Path root, String name, boolean document) {
        return local(root,name,document,false);
    }
    static Path local(Path root, String name, boolean document, boolean allowEmpty) {
        try {
            Path relative=Path.of(name);
            if(relative.isAbsolute()) throw KnowledgeFailure.invalid("KNOWLEDGE_LOCAL_FILE_INVALID");
            Path lexical=root.resolve(relative).normalize();
            if(!lexical.startsWith(root)) throw KnowledgeFailure.invalid("KNOWLEDGE_LOCAL_FILE_INVALID");
            Path path=lexical.toRealPath();
            if(!path.startsWith(root) || !Files.isRegularFile(path) || !Files.isReadable(path) || (!allowEmpty && Files.size(path)==0))
                throw KnowledgeFailure.invalid("KNOWLEDGE_LOCAL_FILE_INVALID");
            String file=path.getFileName().toString().toLowerCase(Locale.ROOT);
            if(document && !(file.endsWith(".pdf") || file.endsWith(".md"))) throw KnowledgeFailure.invalid("KNOWLEDGE_LOCAL_FILE_INVALID");
            return path;
        } catch(KnowledgeFailure failure) { throw failure;
        } catch(Exception failure) { throw KnowledgeFailure.invalid("KNOWLEDGE_LOCAL_FILE_INVALID"); }
    }
    private static List<String> models(JsonNode d,String field,boolean emptyAllowed) {
        JsonNode values=d.get(field);
        if(values==null || !values.isArray() || values.size()>20 || (!emptyAllowed && values.isEmpty())) throw invalid();
        var result=new ArrayList<String>();
        for(JsonNode value:values) {
            String v=string(value,64);
            if(!v.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || result.contains(v)) throw invalid();
            result.add(v);
        }
        return result;
    }
    static String key(String value) {
        if(!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) throw invalid();
        return value;
    }
    private static String text(JsonNode d,String field,int max) { return string(d.get(field),max); }
    private static String string(JsonNode value,int max) {
        if(value==null || !value.isString()) throw invalid();
        String s=value.asString();
        int start=0,end=s.length();
        while(start<end && space(s.codePointAt(start))) start+=Character.charCount(s.codePointAt(start));
        while(end>start && space(s.codePointBefore(end))) end-=Character.charCount(s.codePointBefore(end));
        s=s.substring(start,end);
        if(s.isEmpty() || s.codePointCount(0,s.length())>max || s.codePoints().anyMatch(c->c==0 || (c>=0xD800 && c<=0xDFFF))) throw invalid();
        return s;
    }
    private static boolean space(int c) { return Character.isWhitespace(c)||Character.isSpaceChar(c); }
    private static boolean bool(JsonNode d,String field) {
        JsonNode v=d.get(field);if(v==null || !v.isBoolean()) throw invalid();return v.asBoolean();
    }
    private static LocalDate date(JsonNode d,String field) {
        try {return LocalDate.parse(text(d,field,10));} catch(Exception failure) {throw invalid();}
    }
    private static String url(JsonNode d,String field) {
        String value=text(d,field,2048);
        try {
            URI uri=URI.create(value);
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) throw invalid();
            return value;
        } catch(Exception failure) {throw invalid();}
    }
    private static KnowledgeFailure invalid() { return KnowledgeFailure.invalid("KNOWLEDGE_METADATA_INVALID"); }
}

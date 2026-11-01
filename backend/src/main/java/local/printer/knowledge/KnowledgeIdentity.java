package local.printer.knowledge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import local.printer.knowledge.KnowledgeRows.*;
import tools.jackson.databind.json.JsonMapper;

final class KnowledgeIdentity {
    private KnowledgeIdentity(){}
    static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException();}}
    static String digest(Object... values){
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            for(Object v:values){if(v==null){out.writeInt(-1);continue;}byte[] b=v.toString().getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}
            return sha(bytes.toByteArray());
        }catch(IOException e){throw new IllegalStateException();}
    }
    static String metadata(Document d){return digest(d.documentKey(),d.version(),d.title(),d.sourceType(),d.selfAuthored(),d.simulated(),
        scope(d),d.sourceUrl(),d.officialIndex(),d.termsUrl(),d.brand(),d.region(),d.language(),d.acquiredOn(),d.checkedOn(),
        d.useCondition(),d.distributionAllowed(),d.externalUploadAllowed(),d.localFile(),d.provenanceJson());}
    static String scope(Document d){
        var node=JsonMapper.builder().build().readTree(d.modelsJson());var models=new ArrayList<String>();node.forEach(n->models.add(n.asString()));Collections.sort(models);
        return digest(d.sourceType(),d.selfAuthored(),d.simulated(),d.modelScope(),String.join(",",models));
    }
    static String layout(Document d,List<Chunk> chunks,int size,int overlap){
        var values=new ArrayList<Object>(List.of("knowledge-layout-v1",metadata(d),size,overlap));
        for(var c:chunks){values.add(c.chunkIndex());values.add(c.content());values.add(c.pageNumber());values.add(c.section());}
        return digest(values.toArray());
    }
    static String point(String database,Document d,Chunk c,String layout){
        return UUID.nameUUIDFromBytes(digest(database,d.id(),c.id(),d.contentHash(),layout).getBytes(StandardCharsets.US_ASCII)).toString();
    }
}

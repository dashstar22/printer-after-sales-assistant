package local.printer.knowledge;

import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Explicit local command bootstrap; no HTTP registration and no directory scanning. */
public final class KnowledgeCommand {
    private KnowledgeCommand() {}
    public static boolean requested(String[] args) {
        return Arrays.stream(args).anyMatch(a->a.equals("--register-knowledge") || a.startsWith("--register-knowledge=")
            || a.equals("--extract-knowledge") || a.startsWith("--extract-knowledge=")
            || a.equals("--publish-knowledge") || a.startsWith("--publish-knowledge=")
            || a.equals("--query-knowledge") || a.startsWith("--query-knowledge="));
    }
    public static int run(Class<?> source,String[] args) {
        try {
            var options=new HashMap<String,String>();
            var allowed=Set.of("--register-knowledge","--document-id","--extract-knowledge","--publish-knowledge","--query-knowledge","--model","--chunk-size","--chunk-overlap",
                "--app.knowledge.maintenance-enabled","--app.knowledge.maintenance-root");
            for(String arg:args) {
                int split=arg.indexOf('=');
                if(split<0 || !allowed.contains(arg.substring(0,split)) || arg.substring(split+1).isBlank()
                    || options.put(arg.substring(0,split),arg.substring(split+1))!=null)
                    throw KnowledgeFailure.invalid("KNOWLEDGE_COMMAND_INVALID");
            }
            boolean retrieval=options.containsKey("--query-knowledge");
            if(retrieval){
                if(!options.containsKey("--model")||options.keySet().stream().anyMatch(k->!Set.of("--query-knowledge","--model",
                    "--app.knowledge.maintenance-enabled","--app.knowledge.maintenance-root").contains(k)))throw KnowledgeFailure.invalid("KNOWLEDGE_COMMAND_INVALID");
            }else if(options.containsKey("--model"))throw KnowledgeFailure.invalid("KNOWLEDGE_COMMAND_INVALID");
            boolean publication=options.containsKey("--publish-knowledge");
            boolean extraction=options.containsKey("--extract-knowledge")||publication;
            if(!retrieval&&(options.keySet().stream().filter(k->Set.of("--register-knowledge","--extract-knowledge","--publish-knowledge").contains(k)).count()!=1
                || extraction==options.containsKey("--register-knowledge")
                || (extraction && options.containsKey("--document-id"))
                || (!extraction && (!options.containsKey("--document-id") || options.containsKey("--chunk-size") || options.containsKey("--chunk-overlap")))))
                throw KnowledgeFailure.invalid("KNOWLEDGE_COMMAND_INVALID");
            List<String> ids=List.of();long documentId=0;int size=600,overlap=100;
            if(extraction){
                documentId=number(options.get(publication?"--publish-knowledge":"--extract-knowledge"));
                long s=number(options.getOrDefault("--chunk-size","600"));
                long o=number(options.getOrDefault("--chunk-overlap","100"));
                if(documentId==0 || s>Integer.MAX_VALUE || o>Integer.MAX_VALUE)throw KnowledgeFailure.invalid("KNOWLEDGE_COMMAND_INVALID");
                size=(int)s;overlap=(int)o;KnowledgeTextExtractor.parameters(size,overlap);
            }else if(!retrieval){
                ids=Arrays.asList(options.get("--document-id").split(",",-1));
                if(ids.isEmpty() || ids.size()>20 || new HashSet<>(ids).size()!=ids.size()) throw KnowledgeFailure.invalid("KNOWLEDGE_COMMAND_INVALID");
                ids.forEach(KnowledgeManifest::key);
            }
            var application=new SpringApplication(source);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setDefaultProperties(Map.of("logging.level.root","OFF","spring.main.banner-mode","off"));
            application.setLogStartupInfo(false);
            application.addInitializers(context->{
                if(!"true".equals(context.getEnvironment().getProperty("app.knowledge.maintenance-enabled")))
                    throw KnowledgeFailure.invalid("KNOWLEDGE_MAINTENANCE_DISABLED");
            });
            int count=0;KnowledgeExtractionService.Result extracted=null;KnowledgePublicationService.Result published=null;KnowledgeRetrievalService.Result result=null;
            try(var context=application.run(args)) {
                Path root;
                try { root=Path.of(context.getEnvironment().getProperty("app.knowledge.maintenance-root",".")); }
                catch(InvalidPathException failure) { throw KnowledgeFailure.invalid("KNOWLEDGE_LOCAL_FILE_INVALID"); }
                if(retrieval)result=context.getBean(KnowledgeRetrievalService.class).searchModel(options.get("--model"),query(root,options.get("--query-knowledge")));
                else if(publication)published=context.getBean(KnowledgePublicationService.class).publish(root,documentId,size,overlap);
                else if(extraction)extracted=context.getBean(KnowledgeExtractionService.class).extract(root,documentId,size,overlap);
                else count=context.getBean(KnowledgeRegistrationService.class).register(root,options.get("--register-knowledge"),ids);
            }
            if(retrieval)System.out.println("KNOWLEDGE_QUERY_RESULT "+tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(result));
            else if(publication)System.out.println("KNOWLEDGE_PUBLISHED "+published+" status=READY");
            else if(extraction)System.out.println("KNOWLEDGE_EXTRACTED "+extracted+" status=PENDING active=false");
            else System.out.println("KNOWLEDGE_REGISTERED count="+count+" status=PENDING active=false chunks=0");
            return 0;
        } catch(RuntimeException failure) {
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if(cause instanceof KnowledgeFailure known) {System.err.println(known.getMessage());return known.exitCode();}
            }
            System.err.println("KNOWLEDGE_DATABASE_ERROR");return 3;
        }
    }
    private static String query(Path root,String name){
        try{
            Path file=KnowledgeManifest.local(root.toRealPath(),name,false,true);byte[] bytes;
            try(var input=Files.newInputStream(file)){bytes=input.readNBytes(8193);}
            if(bytes.length>8192)throw KnowledgeFailure.invalid("KNOWLEDGE_QUERY_INVALID");
            String query=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return query.startsWith("\uFEFF")?query.substring(1):query;
        }catch(KnowledgeFailure failure){throw failure;}
        catch(Exception failure){throw KnowledgeFailure.invalid("KNOWLEDGE_QUERY_INVALID");}
    }
    private static long number(String value){
        try{if(!value.matches("[0-9]+"))throw new NumberFormatException();return Long.parseLong(value);}
        catch(NumberFormatException failure){throw KnowledgeFailure.invalid("KNOWLEDGE_COMMAND_INVALID");}
    }
}

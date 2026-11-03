package local.printer.knowledge;

import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import local.printer.administration.AdminFailure;

/** Never a resource handler. Reads only opaque keys generated here, not user paths. */
@Component
public class KnowledgePrivateStorage {
    private final Path configured;
    public KnowledgePrivateStorage(Environment env) {
        configured=Path.of(env.getProperty("app.knowledge.storage-root",".local-validation/knowledge-private")).toAbsolutePath().normalize();
        for(Path part:configured)if(java.util.Set.of("static","public","resources","templates","META-INF").contains(part.toString()))
            throw new IllegalStateException("Knowledge storage must be outside web resource directories");
    }
    private Path root()throws IOException {
        Files.createDirectories(configured);
        if(Files.isSymbolicLink(configured) || !configured.toRealPath().equals(configured))throw new IOException("Invalid storage root");
        return configured;
    }
    public String save(byte[] bytes,String extension) {
        String key=UUID.randomUUID()+"."+extension;
        try {Files.write(root().resolve(key),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);return key;}
        catch(IOException e){throw unavailable();}
    }
    public byte[] read(String key) {
        try {
            if(!key.matches("[a-f0-9-]{36}\\.(md|pdf|docx)"))throw new IOException();
            Path file=root().resolve(key);
            if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>KnowledgeTextExtractor.MAX_BYTES)throw new IOException();
            try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=input.readNBytes(KnowledgeTextExtractor.MAX_BYTES+1);
                if(bytes.length>KnowledgeTextExtractor.MAX_BYTES)throw new IOException();return bytes;
            }
        }catch(IOException e){throw unavailable();}
    }
    public void discardNew(String key) {
        try {if(key.matches("[a-f0-9-]{36}\\.(md|pdf|docx)"))Files.deleteIfExists(root().resolve(key));}
        catch(IOException e){throw unavailable();}
    }
    private static AdminFailure unavailable(){return new AdminFailure(503,"STORAGE_UNAVAILABLE","私有资料暂时不可读写，请保留输入后重试");}
}

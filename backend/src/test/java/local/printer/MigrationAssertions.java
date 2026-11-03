package local.printer;

import java.util.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Assert every migration packaged in this combined workspace, including concurrent additive stages. */
public final class MigrationAssertions {
    private MigrationAssertions(){}
    public static List<String> versions(){
        try{return Arrays.stream(new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql"))
            .map(r->Objects.requireNonNull(r.getFilename()).split("__",2)[0].substring(1)).distinct().sorted(Comparator.comparingInt(Integer::parseInt)).toList();}
        catch(java.io.IOException e){throw new IllegalStateException("MIGRATION_RESOURCES_UNREADABLE");}
    }
}

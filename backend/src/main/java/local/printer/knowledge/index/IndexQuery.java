package local.printer.knowledge.index;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** No customer identity, order or contact data is part of an index request. */
public final class IndexQuery {
    private IndexQuery() {}
    private static final Pattern CODES=Pattern.compile("(?<![A-Z0-9])[A-Z0-9]+(?:[-_][A-Z0-9]+)*(?![A-Z0-9])");
    public record Scope(String model,Set<String> generations) {
        public Scope { generations=Set.copyOf(generations);if(generations.size()>2048)throw new IllegalArgumentException("INDEX_SCOPE_LIMIT"); }
    }
    public record Hit(String generation,String chunkId,double score,String configuration,String manifest,String textHash) {
        public Hit(String generation,String chunkId,double score){this(generation,chunkId,score,null,null,null);}
    }
    public static String normalized(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[‐‑‒–—−]","-");}
    /** Model is already a mandatory applicability filter, not relevance evidence. Keep other error codes intact. */
    public static String lexicalText(String model,String text){
        String canonical=normalized(model);
        if(canonical.isBlank())return normalized(text);
        return Pattern.compile("(?<![A-Z0-9_-])"+Pattern.quote(canonical)+"(?![A-Z0-9_-])")
            .matcher(normalized(text)).replaceAll("");
    }
    public static Set<String> codes(String text){
        var result=new LinkedHashSet<String>();var matcher=CODES.matcher(normalized(text));
        while(matcher.find()){String code=matcher.group();if(code.length()<=64&&code.chars().anyMatch(Character::isDigit))result.add(code);}
        return Set.copyOf(result);
    }
}

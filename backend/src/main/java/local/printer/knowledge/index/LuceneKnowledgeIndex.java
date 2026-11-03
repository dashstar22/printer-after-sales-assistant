package local.printer.knowledge.index;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.*;
import org.apache.lucene.util.BytesRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import static local.printer.knowledge.index.KnowledgeIndexAdapters.*;

/** One filesystem lock/writer per Java instance. All generations remain derived, rebuildable data. */
@Component
@ConditionalOnProperty(name="app.knowledge.hybrid.enabled",havingValue="true")
public class LuceneKnowledgeIndex implements Bm25,AutoCloseable {
    public static final String QUERY_POLICY="bm25-scoped-model-filter-only-v2";
    private final Directory directory;private final IndexWriter writer;
    private final SmartChineseAnalyzer analyzer=new SmartChineseAnalyzer();
    private final SearcherManager readers;
    @org.springframework.beans.factory.annotation.Autowired
    public LuceneKnowledgeIndex(Environment env){this(Path.of(env.getProperty("app.knowledge.lucene-root",".local-validation/knowledge-lucene")));}
    public LuceneKnowledgeIndex(Path root){
        try{
            Path absolute=root.toAbsolutePath().normalize();
            for(Path part:absolute)if(Set.of("static","public","resources","templates","meta-inf").contains(part.toString().toLowerCase(Locale.ROOT)))throw new IOException();
            for(Path p=absolute;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException();
            Files.createDirectories(absolute);directory=FSDirectory.open(absolute);
            writer=new IndexWriter(directory,new IndexWriterConfig(analyzer).setSimilarity(new BM25Similarity()));
            writer.commit();readers=new SearcherManager(writer,null);
        }catch(IOException e){throw failure();}
    }
    public String identity(){return "lucene-9.12.3-smartcn-bm25-v1-k1=1.2-b=0.75-code=4";}
    private static KnowledgeIndexPipeline.IndexFailure failure(){return new KnowledgeIndexPipeline.IndexFailure("BM25_UNAVAILABLE",false,false);}
    @Override public synchronized void write(Generation generation){
        try{
            var documents=new ArrayList<Document>();
            for(var chunk:generation.chunks()){
                var d=new Document();
                d.add(new StringField("generation",generation.id(),Field.Store.YES));
                d.add(new StringField("chunk",chunk.id(),Field.Store.YES));
                d.add(new StoredField("config",generation.configurationHash()));d.add(new StoredField("manifest",generation.manifestHash()));
                d.add(new StoredField("text_hash",KnowledgeIndexPipeline.digest(chunk.text())));
                d.add(new StringField("scope",Objects.toString(generation.metadata().get("modelScope")),Field.Store.NO));
                Object models=generation.metadata().get("models");
                if(models instanceof Collection<?> values)for(Object value:values)d.add(new StringField("model",value.toString(),Field.Store.NO));
                d.add(new TextField("text",chunk.text(),Field.Store.NO));
                for(String code:IndexQuery.codes(chunk.text()))d.add(new StringField("code",code,Field.Store.NO));
                documents.add(d);
            }
            // Atomic generation replacement: a failed write cannot expose a partial replacement to readers.
            writer.updateDocuments(new Term("generation",generation.id()),documents);writer.commit();readers.maybeRefreshBlocking();
        }catch(IOException e){throw failure();}
    }
    public Receipt verify(Generation generation){
        IndexSearcher searcher=null;
        try{
            readers.maybeRefreshBlocking();searcher=readers.acquire();
            var matches=searcher.search(new TermQuery(new Term("generation",generation.id())),10001);
            if(matches.totalHits.value!=generation.chunks().size())throw new KnowledgeIndexPipeline.IndexFailure("INDEX_VERIFICATION_FAILED",true,false);
            var expected=new HashMap<String,String>();generation.chunks().forEach(c->expected.put(c.id(),KnowledgeIndexPipeline.digest(c.text())));
            var ids=new ArrayList<String>();
            for(var hit:matches.scoreDocs){var d=searcher.storedFields().document(hit.doc);String id=d.get("chunk");
                if(!generation.configurationHash().equals(d.get("config"))||!generation.manifestHash().equals(d.get("manifest"))
                    ||!Objects.equals(expected.get(id),d.get("text_hash")))throw new KnowledgeIndexPipeline.IndexFailure("INDEX_VERIFICATION_FAILED",true,false);
                ids.add(id);
            }
            var receipt=new Receipt(generation.id(),generation.configurationHash(),generation.manifestHash(),ids);KnowledgeIndexPipeline.verify(generation,receipt);return receipt;
        }catch(IOException e){throw failure();}finally{if(searcher!=null)try{readers.release(searcher);}catch(IOException ignored){}}
    }
    public List<String> terms(String text){
        try(var stream=analyzer.tokenStream("text",text)){var attribute=stream.addAttribute(CharTermAttribute.class);var terms=new LinkedHashSet<String>();
            stream.reset();while(stream.incrementToken()){if(terms.size()>=256)break;terms.add(attribute.toString());}stream.end();return List.copyOf(terms);
        }catch(IOException e){throw failure();}
    }
    public List<IndexQuery.Hit> search(IndexQuery.Scope scope,String text){
        if(scope.generations().isEmpty())return List.of();IndexSearcher searcher=null;
        try{
            String relevanceQuery=IndexQuery.lexicalText(scope.model(),text);
            var lexical=new BooleanQuery.Builder();for(String term:terms(relevanceQuery))lexical.add(new TermQuery(new Term("text",term)),BooleanClause.Occur.SHOULD);
            for(String code:IndexQuery.codes(relevanceQuery))lexical.add(new BoostQuery(new TermQuery(new Term("code",code)),4),BooleanClause.Occur.SHOULD);
            lexical.setMinimumNumberShouldMatch(1);
            var applicability=new BooleanQuery.Builder().add(new TermQuery(new Term("scope","GENERAL")),BooleanClause.Occur.SHOULD)
                .add(new TermQuery(new Term("model",scope.model())),BooleanClause.Occur.SHOULD).setMinimumNumberShouldMatch(1).build();
            var query=new BooleanQuery.Builder().add(new TermInSetQuery("generation",scope.generations().stream().map(BytesRef::new).toList()),BooleanClause.Occur.FILTER)
                .add(applicability,BooleanClause.Occur.FILTER).add(lexical.build(),BooleanClause.Occur.MUST).build();
            readers.maybeRefreshBlocking();searcher=readers.acquire();var result=new ArrayList<IndexQuery.Hit>();
            for(var hit:searcher.search(query,20).scoreDocs){var d=searcher.storedFields().document(hit.doc);result.add(new IndexQuery.Hit(d.get("generation"),d.get("chunk"),hit.score,d.get("config"),d.get("manifest"),d.get("text_hash")));}
            return result.stream().sorted(Comparator.comparingDouble(IndexQuery.Hit::score).reversed().thenComparing(IndexQuery.Hit::chunkId)).toList();
        }catch(IOException e){throw failure();}finally{if(searcher!=null)try{readers.release(searcher);}catch(IOException ignored){}}
    }
    @PreDestroy @Override public synchronized void close(){try{readers.close();writer.close();directory.close();analyzer.close();}catch(IOException e){throw failure();}}
}

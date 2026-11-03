package local.printer.knowledge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import org.apache.poi.xwpf.usermodel.*;
import local.printer.administration.AdminFailure;

/** Local-only v2 preprocessing. Offsets are Unicode codepoints in the saved extracted text. */
public final class KnowledgePreprocessor {
    public static final String LAYOUT="knowledge-layout-v2";
    public record Slice(String text,Integer page,Integer pageEnd,String section,int start,int end) {}
    public record Result(String text,List<Slice> chunks,int units,int emptyPages) {}
    private static final List<String> BOUNDARIES=List.of("(?<=\n\n)","(?<=\n)","(?<=[。！？!?])","(?<=[；;，,])");
    private KnowledgePreprocessor() {}
    public static void parameters(int size,int overlap) {
        if(size<400 || size>600 || overlap<0 || overlap>100 || overlap>=size)
            throw AdminFailure.invalid("分块大小须为400至600，重叠须为0至100");
    }
    public static Result process(byte[] bytes,String extension,int size,int overlap) {
        parameters(size,overlap);
        if(bytes.length>KnowledgeTextExtractor.MAX_BYTES)throw limit();
        if(bytes.length==0)throw invalid("NO_TEXT");
        int[] empty={0};List<KnowledgeTextExtractor.Located> units;
        try {
            units=switch(extension) {
                case "pdf" -> {
                    if(bytes.length<5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw invalid("TYPE_MISMATCH");
                    yield KnowledgeTextExtractor.pdf(bytes,empty);
                }
                case "md" -> {
                    if(bytes.length>=4 && (bytes[0]=='P' && bytes[1]=='K' || bytes[0]=='%' && bytes[1]=='P'))throw invalid("TYPE_MISMATCH");
                    yield KnowledgeTextExtractor.markdown(KnowledgeTextExtractor.decode(bytes));
                }
                case "docx" -> docx(bytes);
                default -> throw new AdminFailure(415,"FORMAT_UNSUPPORTED","仅支持文本PDF、DOCX、UTF-8 Markdown或FAQ");
            };
        }catch(KnowledgeFailure e){if(e.getMessage().equals("KNOWLEDGE_LIMIT_EXCEEDED"))throw limit();throw invalid(e.getMessage().replace("KNOWLEDGE_",""));}
        StringBuilder canonical=new StringBuilder();var chunks=new ArrayList<Slice>();int offset=0;
        for(var unit:units) {
            String text=KnowledgeTextExtractor.normalize(unit.text());
            if(text.isEmpty())continue;
            if(!canonical.isEmpty()){canonical.append("\n\n");offset+=2;}
            int count=text.codePointCount(0,text.length());
            if((long)offset+count>KnowledgeTextExtractor.MAX_TEXT)throw limit();
            canonical.append(text);
            int[] codepoints=text.codePoints().toArray();
            for(int[] span:spans(text,size,overlap)) {
                chunks.add(new Slice(new String(codepoints,span[0],span[1]-span[0]),unit.page(),unit.page(),unit.section(),offset+span[0],offset+span[1]));
                if(chunks.size()>KnowledgeTextExtractor.MAX_CHUNKS)throw limit();
            }
            offset+=count;
        }
        if(chunks.isEmpty())throw invalid("NO_TEXT");
        return new Result(canonical.toString(),List.copyOf(chunks),units.size(),empty[0]);
    }
    static List<int[]> spans(String text,int size,int overlap) {
        int[] cp=text.codePoints().toArray();var spans=new ArrayList<int[]>();
        for(int start=0;start<cp.length;) {
            int end=Math.min(start+size,cp.length);
            if(end<cp.length)end=boundary(new String(cp,start,Math.min(size,cp.length-start)),start,overlap,end);
            // Avoid cutting complete ASCII error codes, numbered labels and numeric units where possible.
            if(end<cp.length && token(cp[end-1]) && token(cp[end])) {
                int safe=end;while(safe>start+overlap+1 && token(cp[safe-1]))safe--;
                if(safe>start+overlap+1)end=safe;
            }
            spans.add(new int[]{start,end});
            if(spans.size()>KnowledgeTextExtractor.MAX_CHUNKS)throw limit();
            if(end==cp.length)break;
            int next=end-overlap,adjusted=next;
            while(adjusted>Math.max(start+overlap+1,next-50) && token(cp[adjusted-1]) && token(cp[adjusted]))adjusted--;
            if(adjusted>Math.max(start+overlap+1,next-50))next=adjusted;
            start=Math.max(start+1,next);
        }
        return spans;
    }
    private static boolean token(int c){return c<128 && (Character.isLetterOrDigit(c)||c=='_'||c=='-'||c=='.');}
    private static int boundary(String window,int start,int overlap,int fallback) {
        // Each library splitter preserves punctuation through a zero-width lookbehind.
        // The adapter chooses the first usable hierarchy level; no library join/trim rewrites original text.
        for(String regex:BOUNDARIES) {
            String[] pieces=new DocumentByRegexSplitter(regex,"",2000,0).split(window);
            int n=0,best=-1;
            for(int i=0;i<pieces.length-1;i++){n+=pieces[i].codePointCount(0,pieces[i].length());if(n>Math.max(overlap+1,window.codePointCount(0,window.length())/2))best=start+n;}
            if(best>start+overlap)return best;
        }
        return fallback;
    }
    static String sub(String text,int a,int b){return text.substring(text.offsetByCodePoints(0,a),text.offsetByCodePoints(0,b));}
    private static List<KnowledgeTextExtractor.Located> docx(byte[] bytes) {
        if(bytes.length<4 || bytes[0]!='P'||bytes[1]!='K')throw invalid("TYPE_MISMATCH");
        zipBounds(bytes);
        try(var document=new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var units=new ArrayList<KnowledgeTextExtractor.Located>();var body=new StringBuilder();
            var numbering=new HashMap<String,Integer>();
            String[] titles=new String[6];String section=null;int chars=0;
            for(IBodyElement element:document.getBodyElements()) {
                String value;
                if(element instanceof XWPFParagraph p) {
                    value=p.getText();int level=heading(p.getStyle());
                    if(p.getNumID()!=null) {
                        if(p.getNumIlvl()!=null && p.getNumIlvl().intValue()!=0)throw invalid("DOCX_COMPLEX_NUMBERING");
                        String format=p.getNumFmt(),label=p.getNumLevelText();
                        if("decimal".equals(format) && label!=null && label.matches("%1[.)、]?")) {
                            String sequence=p.getNumID().toString();int start=p.getNumStartOverride()==null?1:p.getNumStartOverride().intValue();
                            int number=numbering.compute(sequence,(k,old)->old==null?start:old+1);
                            value=label.replace("%1",Integer.toString(number))+" "+value;
                        }else if("bullet".equals(format) && label!=null && !label.contains("%"))value=label+" "+value;
                        else throw invalid("DOCX_COMPLEX_NUMBERING");
                    }
                    if(level>0 && !value.isBlank()) {
                        add(units,body,section);body.setLength(0);titles[level-1]=value;
                        Arrays.fill(titles,level,6,null);section=String.join(" > ",Arrays.stream(titles).filter(Objects::nonNull).toList());
                        if(section.codePointCount(0,section.length())>500)throw limit();
                    }
                }else if(element instanceof XWPFTable table) {
                    var flat=new StringBuilder();int width=-1;
                    for(var row:table.getRows()) {
                        if(width==-1)width=row.getTableCells().size();
                        if(width!=row.getTableCells().size() || width>50)throw invalid("COMPLEX_TABLE");
                        for(int i=0;i<row.getTableCells().size();i++) {
                            var cell=row.getTableCells().get(i);
                            if(!cell.getTables().isEmpty() || cell.getCTTc().getTcPr()!=null &&
                                (cell.getCTTc().getTcPr().isSetVMerge() || cell.getCTTc().getTcPr().isSetGridSpan()))throw invalid("COMPLEX_TABLE");
                            if(i>0)flat.append('\t');flat.append(cell.getText());
                        }
                        flat.append('\n');
                    }
                    value=flat.toString();
                }else throw invalid("DOCX_UNSUPPORTED_STRUCTURE");
                chars+=value.codePointCount(0,value.length())+2;if(chars>KnowledgeTextExtractor.MAX_TEXT)throw limit();
                body.append(value).append("\n\n");
            }
            add(units,body,section);return units;
        }catch(AdminFailure e){throw e;}
        catch(Exception e){throw invalid("DOCX_UNREADABLE");}
    }
    private static int heading(String style) {
        if(style!=null && style.matches("(?i)(Heading|标题)[ _]?[1-6]"))return style.charAt(style.length()-1)-'0';
        return 0;
    }
    private static void add(List<KnowledgeTextExtractor.Located> units,StringBuilder body,String section) {
        String text=KnowledgeTextExtractor.normalize(body.toString());if(!text.isEmpty())units.add(new KnowledgeTextExtractor.Located(text,null,section));
    }
    private static void zipBounds(byte[] bytes) {
        long total=0;int entries=0;boolean main=false;var names=new HashSet<String>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for(ZipEntry e;(e=zip.getNextEntry())!=null;) {
                if(++entries>2000 || !names.add(e.getName()) || e.getName().startsWith("/") || e.getName().contains("..") || e.getName().contains("\\"))throw invalid("DOCX_ZIP_INVALID");
                if(e.getName().equals("word/document.xml"))main=true;
                long entry=0;byte[] buffer=new byte[8192];int read;
                while((read=zip.read(buffer))!=-1){entry+=read;total+=read;if(total>40L*1024*1024 || entry>20L*1024*1024)throw limit();}
                long compressed=e.getCompressedSize();
                if(entry>1024*1024 && (compressed<=0 || entry>compressed*100))throw invalid("DOCX_COMPRESSION_LIMIT");
            }
        }catch(AdminFailure e){throw e;}
        catch(IOException e){throw invalid("DOCX_ZIP_INVALID");}
        if(!main)throw invalid("TYPE_MISMATCH");
    }
    private static AdminFailure invalid(String code){return new AdminFailure(400,"KNOWLEDGE_"+code,"资料无法可靠提取（"+code+"），请提供文本版或FAQ");}
    private static AdminFailure limit(){return new AdminFailure(413,"RESOURCE_LIMIT","资料超过页数、正文、展开量或片段上限");}
}

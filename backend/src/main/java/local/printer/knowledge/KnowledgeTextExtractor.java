package local.printer.knowledge;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/** Bounded local text extraction only; no OCR, network, rendering or publication. */
final class KnowledgeTextExtractor {
    static final int MAX_BYTES=20*1024*1024, MAX_PAGES=500, MAX_TEXT=2_000_000, MAX_CHUNKS=10_000;
    record Located(String text,Integer page,String section) {
        @Override public String toString(){return "Located[REDACTED]";}
    }
    record Part(String text,Integer page,String section) {
        @Override public String toString(){return "Part[REDACTED]";}
    }
    record Extracted(List<Part> parts,int units,int emptyPages,String sourceHash) {
        @Override public String toString(){return "Extracted[REDACTED]";}
    }
    private static final Pattern ATX=Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*)|$)");
    private static final Pattern SETEXT=Pattern.compile("^ {0,3}(=+|-+)[ \\t]*$");
    private static final Pattern FENCE=Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private KnowledgeTextExtractor(){}

    static Extracted extract(Path root,String file,int size,int overlap) {
        parameters(size,overlap);
        try {
            Path path=KnowledgeManifest.local(root.toRealPath(),file,false,true);
            String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
            if(!name.endsWith(".pdf") && !name.endsWith(".md"))throw fail("FORMAT_UNSUPPORTED");
            if(Files.size(path)>MAX_BYTES)throw fail("LIMIT_EXCEEDED");
            byte[] bytes;
            try(var input=Files.newInputStream(path)){bytes=input.readNBytes(MAX_BYTES+1);}
            if(bytes.length>MAX_BYTES)throw fail("LIMIT_EXCEEDED");
            if(bytes.length==0)throw fail("NO_TEXT");
            int[] empty={0};
            List<Located> units=name.endsWith(".pdf")?pdf(bytes,empty):markdown(decode(bytes));
            int total=0;var parts=new ArrayList<Part>();
            for(var unit:units) {
                String text=normalize(unit.text());
                total+=text.codePointCount(0,text.length());if(total>MAX_TEXT)throw fail("LIMIT_EXCEEDED");
                for(String part:split(text,size,overlap)) {
                    parts.add(new Part(part,unit.page(),unit.section()));
                    if(parts.size()>MAX_CHUNKS)throw fail("LIMIT_EXCEEDED");
                }
            }
            if(parts.isEmpty())throw fail("NO_TEXT");
            return new Extracted(List.copyOf(parts),units.size(),empty[0],KnowledgeIdentity.sha(bytes));
        } catch(KnowledgeFailure failure){throw failure;
        } catch(IOException failure){throw fail("LOCAL_FILE_INVALID");}
    }
    static void parameters(int size,int overlap){
        if(size<100 || size>2000 || overlap<0 || overlap>=size)throw fail("COMMAND_INVALID");
    }
    static String decode(byte[] bytes){
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(text.startsWith("\ufeff"))text=text.substring(1);
            if(text.indexOf('\0')>=0)throw fail("TEXT_INVALID");
            if(text.codePointCount(0,text.length())>MAX_TEXT)throw fail("LIMIT_EXCEEDED");
            return text.replace("\r\n","\n").replace('\r','\n');
        }catch(CharacterCodingException failure){throw fail("TEXT_INVALID");}
    }
    static List<Located> pdf(byte[] bytes,int[] empty){
        try(var document=Loader.loadPDF(bytes)){
            if(document.getNumberOfPages()>MAX_PAGES)throw fail("LIMIT_EXCEEDED");
            var stripper=new PDFTextStripper();stripper.setSortByPosition(true);
            stripper.setParagraphEnd("\n\n");stripper.setLineSeparator("\n");stripper.setPageStart("");stripper.setPageEnd("");
            var units=new ArrayList<Located>();int total=0;
            for(int page=1;page<=document.getNumberOfPages();page++){
                stripper.setStartPage(page);stripper.setEndPage(page);
                var writer=new LimitedWriter((MAX_TEXT-total)*2+2);stripper.writeText(document,writer);
                String text=normalize(writer.toString());
                total+=text.codePointCount(0,text.length());if(total>MAX_TEXT)throw fail("LIMIT_EXCEEDED");
                if(text.isEmpty()){empty[0]++;continue;}
                units.add(new Located(text,page,null));
            }
            return units;
        }catch(KnowledgeFailure failure){throw failure;
        }catch(IOException|RuntimeException failure){throw fail("PDF_UNREADABLE");}
    }
    private static final class LimitedWriter extends Writer {
        private final StringBuilder text=new StringBuilder();private final int limit;
        LimitedWriter(int limit){this.limit=limit;}
        @Override public void write(char[] chars,int offset,int length){
            if((long)text.length()+length>limit)throw fail("LIMIT_EXCEEDED");text.append(chars,offset,length);
        }
        @Override public void flush(){}
        @Override public void close(){}
        @Override public String toString(){return text.toString();}
    }
    static List<Located> markdown(String text){
        String[] lines=text.split("\n",-1);var units=new ArrayList<Located>();
        String[] headings=new String[6];String section=null;var body=new StringBuilder();
        char fence=0;int fenceLength=0;
        for(int i=0;i<lines.length;i++){
            String line=lines[i];var fm=FENCE.matcher(line);
            if(fence!=0){
                body.append(line).append('\n');
                if(fm.matches() && fm.group(1).charAt(0)==fence && fm.group(1).length()>=fenceLength && fm.group(2).isBlank())fence=0;
                continue;
            }
            if(fm.matches()){fence=fm.group(1).charAt(0);fenceLength=fm.group(1).length();body.append(line).append('\n');continue;}
            var atx=ATX.matcher(line);int level=0;String title=null,headingLine=line;
            if(atx.matches()){
                level=atx.group(1).length();title=atx.group(2)==null?"":atx.group(2).replaceFirst("[ \\t]+#+[ \\t]*$","");
            }else if(i+1<lines.length && !line.isBlank() && !line.matches("^\\s*(?:[-+*>]|[0-9]+[.)])\\s+.*")){
                var underline=SETEXT.matcher(lines[i+1]);
                if(underline.matches()){level=underline.group(1).charAt(0)=='='?1:2;title=line;headingLine=line+'\n'+lines[++i];}
            }
            if(level>0 && title!=null && !normalize(title).isEmpty()){
                addUnit(units,body,section);body.setLength(0);
                headings[level-1]=normalize(title);Arrays.fill(headings,level,6,null);
                section=String.join(" > ",Arrays.stream(headings).filter(Objects::nonNull).toList());
                if(section.codePointCount(0,section.length())>500)throw fail("LIMIT_EXCEEDED");
                body.append(headingLine).append('\n');
            }else body.append(line).append('\n');
        }
        addUnit(units,body,section);return units;
    }
    private static void addUnit(List<Located> units,StringBuilder body,String section){
        String text=normalize(body.toString());if(!text.isEmpty())units.add(new Located(text,null,section));
    }
    static List<String> split(String input,int size,int overlap){
        parameters(size,overlap);String text=normalize(input);if(text.isEmpty())return List.of();
        int[] cp=text.codePoints().toArray();var result=new ArrayList<String>();
        for(int start=0;start<cp.length;){
            int end=Math.min(start+size,cp.length);
            if(end<cp.length){
                // End after a complete empty-line separator, while guaranteeing forward progress.
                for(int j=end-1;j>start+overlap;j--){if(cp[j]=='\n' && cp[j-1]=='\n'){end=j+1;break;}}
            }
            String part=new String(cp,start,end-start);
            if(!part.codePoints().allMatch(KnowledgeTextExtractor::space))result.add(part);
            if(result.size()>MAX_CHUNKS)throw fail("LIMIT_EXCEEDED");
            if(end==cp.length)break;
            start=end-overlap;
        }
        return result;
    }
    static String normalize(String text){
        text=text.replace("\r\n","\n").replace('\r','\n');int a=0,b=text.length();
        while(a<b && space(text.codePointAt(a)))a+=Character.charCount(text.codePointAt(a));
        while(b>a && space(text.codePointBefore(b)))b-=Character.charCount(text.codePointBefore(b));
        return text.substring(a,b);
    }
    private static boolean space(int c){return Character.isWhitespace(c)||Character.isSpaceChar(c);}
    private static KnowledgeFailure fail(String code){return KnowledgeFailure.invalid("KNOWLEDGE_"+code);}
}

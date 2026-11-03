package local.printer.ticket;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 只读取 JPEG/PNG 像素；新图像不携带用户提供的 EXIF/ICC/文本元数据。 */
@Component
public class TicketPhotoStore {
    static final int MAX_BYTES=5*1024*1024;
    private final Path root;
    public TicketPhotoStore(@Value("${app.tickets.photo-root:.local-validation/private-ticket-photos}") String root){this.root=Path.of(root).toAbsolutePath().normalize();}
    record Photo(byte[] bytes,String inputHash,String hash,String mime,int width,int height){}
    static TicketHandoffService.Failure limit(){return new TicketHandoffService.Failure(413,"RESOURCE_LIMIT","照片限每张5MB、单边10000像素且总像素2000万");}
    static TicketHandoffService.Failure type(){return new TicketHandoffService.Failure(415,"UNSUPPORTED_MEDIA_TYPE","请上传可解码的JPEG或PNG照片");}
    static TicketHandoffService.Failure unavailable(){return new TicketHandoffService.Failure(503,"ATTACHMENT_UNAVAILABLE","照片暂时不可用，请保留原请求标识重试");}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    Photo decode(MultipartFile file){
        if(file.getSize()>MAX_BYTES)throw limit();if(file.isEmpty())throw type();
        String name=Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
        String format=name.endsWith(".png")?"png":name.endsWith(".jpg")||name.endsWith(".jpeg")?"jpeg":null;
        if(format==null || !Objects.equals(file.getContentType(),"image/"+format))throw type();
        byte[] input;
        try(var in=file.getInputStream()){input=in.readNBytes(MAX_BYTES+1);}catch(IOException failure){throw unavailable();}
        if(input.length>MAX_BYTES)throw limit();
        if(format.equals("png") ? input.length<8 || !Arrays.equals(Arrays.copyOf(input,8),new byte[]{(byte)137,80,78,71,13,10,26,10})
            : input.length<3 || (input[0]&255)!=255 || (input[1]&255)!=216 || (input[2]&255)!=255)throw type();
        try(var in=new MemoryCacheImageInputStream(new ByteArrayInputStream(input))){
            var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw type();var reader=readers.next();
            try {
                if(!reader.getFormatName().equalsIgnoreCase(format))throw type();
                reader.setInput(in,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||width>10000||height>10000||(long)width*height>20_000_000)throw limit();
                BufferedImage decoded=reader.read(0);if(decoded==null)throw type();
                // New raster + metadata-free write also strips PNG text and JPEG GPS/EXIF.
                BufferedImage clean=new BufferedImage(width,height,format.equals("jpeg")?BufferedImage.TYPE_INT_RGB:BufferedImage.TYPE_INT_ARGB);
                var graphics=clean.createGraphics();try{graphics.drawImage(decoded,0,0,null);}finally{graphics.dispose();decoded.flush();}
                try(var out=new ByteArrayOutputStream()){
                    if(!ImageIO.write(clean,format,out))throw type();
                    if(out.size()>MAX_BYTES)throw limit();
                    byte[] bytes=out.toByteArray();return new Photo(bytes,hash(input),hash(bytes),"image/"+format,width,height);
                }finally{clean.flush();}
            }finally{reader.dispose();}
        }catch(IOException|IllegalArgumentException malformed){throw type();}
    }
    private void directory()throws IOException {
        Files.createDirectories(root);
        // A configured private volume must not redirect through a symbolic link.
        for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("private directory redirected");
    }
    private Path path(String key){
        if(key==null || !key.matches("[0-9a-f-]{36}\\.(jpg|png)"))throw unavailable();
        Path p=root.resolve(key).normalize();if(!p.getParent().equals(root)||Files.isSymbolicLink(p))throw unavailable();return p;
    }
    void write(String key,byte[] bytes){
        boolean created=false;
        try{directory();
            try(var out=Files.newOutputStream(path(key),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){created=true;out.write(bytes);}}
        catch(IOException failure){if(created)discard(key);throw unavailable();}
    }
    byte[] read(String key,String expected){
        try{directory();byte[] bytes;try(var in=Files.newInputStream(path(key),LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(MAX_BYTES+1);}
            if(bytes.length>MAX_BYTES||!hash(bytes).equals(expected))throw unavailable();return bytes;}
        catch(IOException failure){throw unavailable();}
    }
    void discard(String key){try{Files.deleteIfExists(path(key));}catch(IOException ignored){/* orphan retained for explicit local reconciliation */}}
}

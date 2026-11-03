package local.printer.ticket;

import java.util.*;
import local.printer.auth.SessionUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TicketAttachmentService {
    private final TicketHandoffService handoff;
    private final TicketPhotoStore files;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public TicketAttachmentService(TicketHandoffService handoff,TicketPhotoStore files,JdbcTemplate db,ObjectMapper json){this.handoff=handoff;this.files=files;this.db=db;this.json=json;}
    @Transactional
    public JsonNode upload(SessionUser.Identity actor,long id,long version,String request,List<MultipartFile> input){
        TicketHandoffService.customer(actor);TicketHandoffService.requestId(request);
        // Authorize before touching image bytes; serialize total count + close/upload races on the ticket.
        var row=handoff.ticket(actor,id,true);
        if(input==null||input.isEmpty()||input.size()>3)throw new TicketHandoffService.Failure(413,"RESOURCE_LIMIT","每次上传1至3张照片，每工单最多10张");
        var photos=input.stream().map(files::decode).toList();
        String hash=handoff.fingerprint(List.of(version,photos.stream().map(TicketPhotoStore.Photo::inputHash).toList()));
        var old=handoff.replay(id,actor.id(),"ATTACHMENTS",request,hash);if(old!=null)return old;
        handoff.writable(row,version);
        if(db.queryForObject("SELECT COUNT(*) FROM ticket_attachments WHERE ticket_id=?",Long.class,id)+photos.size()>10)
            throw new TicketHandoffService.Failure(413,"RESOURCE_LIMIT","每工单最多10张照片");
        var created=new ArrayList<String>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){if(status==STATUS_ROLLED_BACK)created.forEach(files::discard);}
        });
        var items=new ArrayList<Map<String,Object>>();int index=0;
        for(var photo:photos){
            String key=UUID.randomUUID()+(photo.mime().equals("image/png")?".png":".jpg");files.write(key,photo.bytes());created.add(key);
            db.update("INSERT INTO ticket_attachments(ticket_id,customer_id,request_id,part_index,storage_key,sha256,mime,width,height,bytes,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id,actor.id(),request,index++,key,photo.hash(),photo.mime(),photo.width(),photo.height(),photo.bytes().length,handoff.now());
            long attachment=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            items.add(Map.of("id",attachment,"mime",photo.mime(),"width",photo.width(),"height",photo.height(),"bytes",photo.bytes().length,
                "url","/api/tickets/"+id+"/attachments/"+attachment));
        }
        handoff.advance(id,version);
        long event=handoff.event(id,actor.id(),"PHOTOS_ADDED","客户补充故障照片 "+items.size()+" 张；附件："+items.stream().map(i->i.get("id").toString()).toList());
        return handoff.remember(id,actor.id(),"ATTACHMENTS",request,hash,Map.of("id",id,"version",version+1,"items",items,"eventId",event));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public JsonNode list(SessionUser.Identity actor,long id){
        handoff.ticket(actor,id,false);
        return json.valueToTree(Map.of("items",db.queryForList("SELECT id,mime,width,height,bytes,created_at FROM ticket_attachments WHERE ticket_id=? ORDER BY id",id).stream().map(r->{
            var item=new LinkedHashMap<String,Object>(r);item.put("createdAt",TicketHandoffService.instant(item.remove("created_at")));
            item.put("url","/api/tickets/"+id+"/attachments/"+r.get("id"));return item;}).toList()));
    }
    public record Download(byte[] bytes,String mime){}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Download read(SessionUser.Identity actor,long id,long attachment){
        handoff.ticket(actor,id,false);
        var rows=db.queryForList("SELECT storage_key,sha256,mime FROM ticket_attachments WHERE ticket_id=? AND id=?",id,attachment);
        if(rows.isEmpty())throw TicketHandoffService.missing();var r=rows.get(0);
        return new Download(files.read((String)r.get("storage_key"),(String)r.get("sha256")),(String)r.get("mime"));
    }
}

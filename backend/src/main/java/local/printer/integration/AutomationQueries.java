package local.printer.integration;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Same ticket/event facts as the customer and staff services, with a deliberately smaller projection. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class AutomationQueries {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final AutomationSettings settings;
    private static final String CREATED="COALESCE(t.created_at,(SELECT MIN(e.created_at) FROM ticket_events e WHERE e.ticket_id=t.id AND e.event_type='CREATED'))";
    private static final String UPDATED="COALESCE(t.updated_at,(SELECT MAX(e.created_at) FROM ticket_events e WHERE e.ticket_id=t.id))";
    private static final String OVERDUE="((t.status='PENDING' AND "+CREATED+"<=?) OR (t.status='IN_PROGRESS' AND "+UPDATED+"<=?))";
    AutomationQueries(JdbcTemplate db,AutomationSettings settings){this.db=db;this.settings=settings;}
    static LocalDate date(String input){
        LocalDate today=LocalDate.now(ZONE),d=input==null?today.minusDays(1):LocalDate.parse(input);
        if(!d.isBefore(today) || d.isBefore(today.minusDays(31)))throw new IllegalArgumentException("DATE_OUT_OF_RANGE");return d;
    }
    static void fields(Map<String,Object> args,Set<String> allowed){if(args==null || !allowed.containsAll(args.keySet()))throw new IllegalArgumentException("INVALID_ARGUMENT");}
    static long integer(Object o,long fallback,long min,long max){
        if(o==null)return fallback;
        if(!(o instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.longValue() || n.longValue()<min || n.longValue()>max)throw new IllegalArgumentException("INVALID_ARGUMENT");return n.longValue();
    }
    public Map<String,Object> overdue(Map<String,Object> args){
        fields(args,Set.of("cursor","pageSize","asOf"));
        long cursor=integer(args.get("cursor"),0,0,Long.MAX_VALUE),size=integer(args.get("pageSize"),20,1,100);
        Instant now=Instant.now(),asOf=args.get("asOf")==null?now:Instant.parse((String)args.get("asOf"));
        if(Duration.between(now,asOf).abs().compareTo(Duration.ofMinutes(5))>0)throw new IllegalArgumentException("AS_OF_OUT_OF_RANGE");
        var rows=db.query("SELECT t.id,t.ticket_no,t.status,t.version,COALESCE(u.display_name,'未指定') FROM tickets t LEFT JOIN users u ON u.id=t.accepted_by WHERE t.id>? AND "+OVERDUE+" ORDER BY t.id LIMIT ?",
            (r,n)->new LinkedHashMap<String,Object>(Map.of("cursor",r.getLong(1),"ticketNo",r.getString(2),"status",r.getString(3),"version",r.getLong(4),"assigneeDisplayName",r.getString(5),"link",settings.ticketLink())),
            cursor,utc(asOf.minus(Duration.ofHours(settings.pendingHours))),utc(asOf.minus(Duration.ofHours(settings.inactiveHours))),size+1);
        boolean more=rows.size()>size;if(more)rows.remove(rows.size()-1);
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("asOf",asOf.toString());result.put("businessDate",LocalDate.ofInstant(asOf,ZONE).toString());
        result.put("window",LocalDateTime.ofInstant(asOf,ZONE).withSecond(0).withNano(0).withMinute((LocalDateTime.ofInstant(asOf,ZONE).getMinute()/15)*15).toString());
        result.put("nextCursor",more?rows.get(rows.size()-1).get("cursor"):null);return result;
    }
    public Map<String,Object> summary(Map<String,Object> args){
        fields(args,Set.of("date"));var d=date((String)args.get("date"));
        var counts=new LinkedHashMap<String,Object>();
        for(String status:List.of("PENDING","IN_PROGRESS","CLOSED"))counts.put(status,db.queryForObject("SELECT COUNT(*) FROM tickets t WHERE "+CREATED+">=? AND "+CREATED+"<? AND t.status=?",Long.class,utc(d.atStartOfDay(ZONE).toInstant()),utc(d.plusDays(1).atStartOfDay(ZONE).toInstant()),status));
        return Map.of("date",d.toString(),"timezone",ZONE.toString(),"counts",counts,"countBasis","created_on_date_current_status","link",settings.ticketLink());
    }
    boolean eligible(String no,long version){return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM tickets t WHERE t.ticket_no=? AND t.version=? AND "+OVERDUE,Boolean.class,no,version,utc(Instant.now().minus(Duration.ofHours(settings.pendingHours))),utc(Instant.now().minus(Duration.ofHours(settings.inactiveHours)))));}
    static LocalDateTime utc(Instant time){return LocalDateTime.ofInstant(time,ZoneOffset.UTC);}
}

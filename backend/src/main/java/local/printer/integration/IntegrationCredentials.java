package local.printer.integration;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IntegrationCredentials {
    private final JdbcTemplate db;
    IntegrationCredentials(JdbcTemplate db){this.db=db;}
    public record Identity(String id,Set<String> scopes) {}
    static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    Identity authenticate(String token){
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))return null;
        var rows=db.query("SELECT id,scopes FROM integration_credentials WHERE token_hash=? AND revoked_at IS NULL",
            (r,n)->new Identity(r.getString(1),Set.copyOf(Arrays.asList(r.getString(2).split(",")))),hash(token));
        return rows.isEmpty()?null:rows.get(0);
    }
    public String create(String id,String label,String scopes,String token){
        UUID.fromString(id);
        if(label==null || !label.matches("[A-Za-z0-9_-]{1,80}") || token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException();
        var allowed=Set.of("automation:read","notification:write");
        var values=AutomationSettings.csv(scopes==null?"":scopes);
        if(values.isEmpty() || !allowed.containsAll(values))throw new IllegalArgumentException();
        db.update("INSERT INTO integration_credentials(id,label,token_hash,scopes) VALUES (?,?,?,?)",id,label,hash(token),String.join(",",new TreeSet<>(values)));
        return id;
    }
    public void revoke(String id){UUID.fromString(id);db.update("UPDATE integration_credentials SET revoked_at=UTC_TIMESTAMP(6) WHERE id=? AND revoked_at IS NULL",id);}
}

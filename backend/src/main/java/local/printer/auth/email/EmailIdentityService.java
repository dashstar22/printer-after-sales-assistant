package local.printer.auth.email;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import local.printer.administration.*;
import local.printer.auth.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Guard -> current account -> purpose token -> unique email. Every identity mutation uses this order. */
@Service
public class EmailIdentityService {
    private final AdminLedger ledger;
    private final JdbcTemplate db;
    private final Environment env;
    private final IdentityMailSender mail;
    private final AuthUserMapper users;
    private final PasswordEncoder passwords=PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private local.printer.operations.RedisProtection shared;
    @org.springframework.beans.factory.annotation.Autowired
    public void shared(local.printer.operations.RedisProtection value){shared=value;}
    private final SecureRandom random=new SecureRandom();
    public EmailIdentityService(AdminLedger ledger,Environment env,IdentityMailSender mail,AuthUserMapper users){this.ledger=ledger;this.db=ledger.db();this.env=env;this.mail=mail;this.users=users;}
    public record Delivery(long sendId,String email,String purpose,String token,int minutes) {
        @Override public String toString(){return "Delivery[REDACTED]";}
    }
    public record InvitationResult(JsonNode result,Delivery delivery){@Override public String toString(){return "InvitationResult[REDACTED]";}}
    public static final Map<String,Object> ACCEPTED=Map.of("accepted",true,"message","如可受理，将向邮箱发送操作链接，请检查收件箱并稍后重试。");
    public static String digest(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static void fields(JsonNode b,String... keys){if(b==null || !b.isObject() || !b.propertyNames().equals(Set.of(keys)))throw EmailFailure.invalid();}
    public static String text(JsonNode b,String key){var v=b.get(key);if(v==null || !v.isString())throw EmailFailure.invalid();return v.asString();}
    public static String newPassword(JsonNode b){String p=AdminInput.password(text(b,"password"));if(!p.equals(text(b,"passwordConfirmation")))throw EmailFailure.invalid();return p;}
    private EmailAddress address(JsonNode b){return EmailAddress.parse(text(b,"email"));}
    public boolean migrationEnabled(){return "MIGRATION_ONLY".equals(env.getProperty("LEGACY_LOGIN_MODE","DISABLED"));}
    public SessionUser login(String identifier) {
        AuthUserMapper.Account a=null;
        if(identifier!=null && identifier.contains("@")) {
            try{a=users.findByEmail(EmailAddress.parse(identifier).normalized());}catch(EmailFailure invalid){/* uniform failure */}
            if(a==null || !a.emailVerified())throw new UsernameNotFoundException("账号或密码错误");
        }else {
            if(identifier!=null)a=users.findByUsername(identifier.strip().toLowerCase(Locale.ROOT));
            if(a==null || a.emailVerified() || (a.recoveryPending()?!a.recoveryActive():!migrationEnabled()))throw new UsernameNotFoundException("账号或密码错误");
        }
        if(!Set.of("CUSTOMER","STAFF","ADMIN").contains(a.role()))throw new UsernameNotFoundException("账号或密码错误");
        return new SessionUser(a,true);
    }
    @Transactional public Delivery request(String purpose,JsonNode body) {
        fields(body,"email");EmailAddress email=address(body);mail.requireReady();
        ledger.lock();
        var rows=db.queryForList("SELECT id,status,email_verified_at,credential_version FROM users WHERE email=?",email.normalized());
        if(purpose.equals("REGISTER")) {
            if(!rows.isEmpty())return null;
            return issue(purpose,email,null,null,null);
        }
        if(!purpose.equals("RESET"))throw EmailFailure.invalid();
        if(rows.isEmpty() || !"ACTIVE".equals(rows.get(0).get("status")) || rows.get(0).get("email_verified_at")==null)return null;
        var u=rows.get(0);return issue(purpose,email,number(u,"id"),number(u,"credential_version"),null);
    }
    private Delivery issue(String purpose,EmailAddress email,Long user,Long version,Long invitation) {
        String hash=digest(email.normalized());
        if(db.queryForObject("SELECT COUNT(*) FROM email_sends WHERE email_digest=? AND purpose=? AND created_at>CURRENT_TIMESTAMP(6)-INTERVAL 60 SECOND",Long.class,hash,purpose)>0
            || db.queryForObject("SELECT COUNT(*) FROM email_sends WHERE email_digest=? AND created_at>CURRENT_TIMESTAMP(6)-INTERVAL 1 HOUR",Long.class,hash)>=5
            || db.queryForObject("SELECT COUNT(*) FROM email_sends WHERE created_at>=UTC_DATE()",Long.class)>=mail.dailyCap())return null;
        // SQL remains authoritative across Redis outages/restarts. Redis can only suppress additional sends.
        if(shared!=null && (!shared.allow("mail-cooldown",hash+":"+purpose,1,Duration.ofSeconds(60))
            ||!shared.allow("mail-hour",hash,5,Duration.ofHours(1))
            ||!shared.allow("mail-day","all",mail.dailyCap(),Duration.ofDays(1))))return null;
        if(purpose.equals("BIND"))db.update("UPDATE email_actions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE purpose='BIND' AND user_id=? AND consumed_at IS NULL AND revoked_at IS NULL",user);
        else db.update("UPDATE email_actions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE email=? AND purpose=? AND consumed_at IS NULL AND revoked_at IS NULL",email.normalized(),purpose);
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        int minutes=switch(purpose){case "REGISTER","BIND"->30;case "RESET"->15;case "INVITE"->2880;default->throw EmailFailure.invalid();};
        db.update("INSERT INTO email_actions(purpose,email,display_email,user_id,credential_version,invitation_id,token_digest,expires_at) VALUES(?,?,?,?,?,?,?,TIMESTAMPADD(MINUTE,?,CURRENT_TIMESTAMP(6)))",
            purpose,email.normalized(),email.display(),user,version,invitation,digest(token),minutes);
        long action=ledger.inserted();
        db.update("INSERT INTO email_sends(action_id,email_digest,purpose) VALUES(?,?,?)",action,hash,purpose);
        return new Delivery(ledger.inserted(),email.normalized(),purpose,token,minutes);
    }
    @Transactional public Map<String,Object> confirm(String purpose,JsonNode b,SessionUser actor) {
        if(purpose.equals("BIND"))fields(b,"token");else fields(b,"token","password","passwordConfirmation");
        String token=text(b,"token");if(!token.matches("[A-Za-z0-9_-]{43}"))throw EmailFailure.token();
        String password=purpose.equals("BIND")?null:newPassword(b);
        ledger.lock();
        var rows=db.queryForList("SELECT *,expires_at>CURRENT_TIMESTAMP(6) AS unexpired FROM email_actions WHERE token_digest=? FOR UPDATE",digest(token));
        if(rows.isEmpty())throw EmailFailure.token();
        var action=rows.get(0);
        if(!purpose.equals(action.get("purpose")) || action.get("consumed_at")!=null || action.get("revoked_at")!=null || !truth(action.get("unexpired")))throw EmailFailure.token();
        String email=(String)action.get("email"),display=(String)action.get("display_email");
        Long user=action.get("user_id")==null?null:number(action,"user_id");
        if(purpose.equals("REGISTER") || purpose.equals("INVITE")) {
            if(exists(email))throw EmailFailure.conflict();
            if(purpose.equals("INVITE")) {
                var invitation=invitation(number(action,"invitation_id"));
                validInvitation(invitation);
                if(!email.equals(invitation.get("email")))throw EmailFailure.token();
                db.update("UPDATE email_invitations SET status='ACCEPTED' WHERE id=?",number(invitation,"id"));
            }
            String username="email_"+UUID.randomUUID().toString().replace("-","");
            db.update("INSERT INTO users(username,password_hash,role,email,display_email,email_verified_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP(6))",
                username,passwords.encode(password),purpose.equals("REGISTER")?"CUSTOMER":"STAFF",email,display);
            user=ledger.inserted();
        }else {
            if(user==null)throw EmailFailure.token();
            var account=account(user);
            if(!"ACTIVE".equals(account.get("status")) || number(action,"credential_version")!=number(account,"credential_version"))throw EmailFailure.token();
            if(purpose.equals("RESET")) {
                if(!email.equals(account.get("email")) || account.get("email_verified_at")==null)throw EmailFailure.token();
                db.update("UPDATE users SET password_hash=?,must_change_password=FALSE,credential_version=credential_version+1,version=version+1 WHERE id=?",passwords.encode(password),user);
            }else if(purpose.equals("BIND")) {
                requireMigration(actor,account);
                if(exists(email))throw EmailFailure.conflict();
                db.update("UPDATE users SET email=?,display_email=?,email_verified_at=CURRENT_TIMESTAMP(6),recovery_until=NULL,credential_version=credential_version+1,version=version+1 WHERE id=?",email,display,user);
            }else throw EmailFailure.token();
            db.update("DELETE FROM persistent_logins WHERE username=?",account.get("username"));
        }
        int used=db.update("UPDATE email_actions SET consumed_at=CURRENT_TIMESTAMP(6) WHERE id=? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP(6)",number(action,"id"));
        if(used!=1)throw EmailFailure.token();
        if(purpose.equals("REGISTER") || purpose.equals("INVITE"))
            db.update("UPDATE email_actions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE email=? AND purpose=? AND consumed_at IS NULL AND revoked_at IS NULL",email,purpose);
        else db.update("UPDATE email_actions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL",user);
        audit(user,"EMAIL_"+purpose,"SUCCESS");
        return Map.of("completed",true,"loginRequired",true);
    }
    @Transactional public Delivery bindRequest(SessionUser actor,JsonNode b) {
        fields(b,"email","currentPassword");EmailAddress email=address(b);mail.requireReady();ledger.lock();
        var account=account(actor.identity().id());requireMigration(actor,account);
        if(!passwords.matches(text(b,"currentPassword"),(String)account.get("password_hash")))throw new EmailFailure(400,"PASSWORD_INVALID","当前密码不正确");
        if(exists(email.normalized()))throw EmailFailure.conflict();
        return issue("BIND",email,actor.identity().id(),actor.credentialVersion(),null);
    }
    private void requireMigration(SessionUser actor,Map<String,Object> a) {
        if(actor==null || !actor.passwordAuthenticated() || actor.identity().id()!=number(a,"id") || actor.credentialVersion()!=number(a,"credential_version")
            || !"ACTIVE".equals(a.get("status")) || a.get("email_verified_at")!=null || truth(a.get("must_change_password"))
            || (a.get("recovery_until")!=null?!truth(a.get("recovery_active")):!migrationEnabled()))throw new EmailFailure(403,"EMAIL_BIND_FORBIDDEN","请用原账号当前密码登录，并先完成必要改密");
    }
    @Transactional public JsonNode migration(SessionUser actor) {
        var a=account(actor.identity().id());
        String display=Objects.toString(a.get("display_name"),"").strip();
        if(display.isEmpty() && a.get("email_verified_at")!=null)display=Objects.toString(a.get("display_email"),Objects.toString(a.get("email"),"")).strip();
        if(display.isEmpty())display=Objects.toString(a.get("username"),"");
        return ledger.node(Map.of("userId",actor.identity().id(),"emailVerified",a.get("email_verified_at")!=null,"passwordChangeRequired",truth(a.get("must_change_password")),
            "passwordAuthenticated",actor.passwordAuthenticated(),"legacyMode",migrationEnabled()?"MIGRATION_ONLY":"DISABLED","recoveryActive",truth(a.get("recovery_active")),"displayName",display));
    }
    @Transactional public InvitationResult invite(SessionUser actor,JsonNode b) {
        fields(b,"email","requestId");EmailAddress email=address(b);mail.requireReady();
        Delivery[] delivery={null};
        JsonNode result=ledger.once(actor,"STAFF_INVITE","new",b,()->{
            if(exists(email.normalized()))throw EmailFailure.conflict();
            if(db.queryForObject("SELECT COUNT(*) FROM email_invitations WHERE email=? AND status='PENDING' AND expires_at>CURRENT_TIMESTAMP(6)",Long.class,email.normalized())>0)
                throw new EmailFailure(409,"INVITATION_PENDING","该邮箱已有待处理邀请，请重发或撤销");
            db.update("INSERT INTO email_invitations(email,display_email,inviter_id,inviter_version,expires_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP(6)+INTERVAL 48 HOUR)",email.normalized(),email.display(),actor.identity().id(),actor.credentialVersion());
            long id=ledger.inserted();delivery[0]=issue("INVITE",email,null,null,id);
            return ledger.node(Map.of("id",id,"status","PENDING","mailAccepted",delivery[0]!=null));
        });
        return new InvitationResult(result,delivery[0]);
    }
    @Transactional public InvitationResult invitationAction(SessionUser actor,long id,JsonNode b,boolean revoke) {
        fields(b,"requestId");if(!revoke)mail.requireReady();Delivery[] delivery={null};
        JsonNode result=ledger.once(actor,revoke?"INVITE_REVOKE":"INVITE_RESEND",Long.toString(id),b,()->{
            var invite=invitation(id);
            if(!"PENDING".equals(invite.get("status")))throw EmailFailure.token();
            if(revoke) {
                db.update("UPDATE email_invitations SET status='REVOKED' WHERE id=?",id);
                db.update("UPDATE email_actions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE invitation_id=? AND consumed_at IS NULL AND revoked_at IS NULL",id);
            }else {
                if(exists((String)invite.get("email")))throw EmailFailure.conflict();
                // A resend is a new authorization from the currently active, verified administrator.
                db.update("UPDATE email_invitations SET inviter_id=?,inviter_version=?,expires_at=CURRENT_TIMESTAMP(6)+INTERVAL 48 HOUR WHERE id=?",actor.identity().id(),actor.credentialVersion(),id);
                delivery[0]=issue("INVITE",new EmailAddress((String)invite.get("email"),(String)invite.get("display_email")),null,null,id);
            }
            return ledger.node(Map.of("id",id,"status",revoke?"REVOKED":"PENDING","mailAccepted",delivery[0]!=null));
        });
        return new InvitationResult(result,delivery[0]);
    }
    @Transactional(readOnly=true) public JsonNode invitations(SessionUser actor,long cursor,int size) {
        ledger.actor(actor,true);
        return ledger.page("SELECT id,CONCAT(LEFT(email,1),'***@',SUBSTRING_INDEX(email,'@',-1)) AS maskedEmail,inviter_id AS inviterId,status,expires_at AS expiresAt FROM email_invitations",cursor,size);
    }
    private Map<String,Object> invitation(long id){var rows=db.queryForList("SELECT *,expires_at>CURRENT_TIMESTAMP(6) AS unexpired FROM email_invitations WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw EmailFailure.token();return rows.get(0);}
    private void validInvitation(Map<String,Object> i) {
        if(!"PENDING".equals(i.get("status")) || !truth(i.get("unexpired")))throw EmailFailure.token();
        var a=account(number(i,"inviter_id"));
        if(!"ADMIN".equals(a.get("role")) || !"ACTIVE".equals(a.get("status")) || a.get("email_verified_at")==null || truth(a.get("must_change_password")) || number(a,"credential_version")!=number(i,"inviter_version"))throw EmailFailure.token();
    }
    @Transactional(readOnly=true) public JsonNode preflight(SessionUser actor) {
        ledger.actor(actor,true);
        return ledger.node(Map.of("mode",migrationEnabled()?"MIGRATION_ONLY":"DISABLED","accounts",db.queryForList("SELECT id,username,role,status,(email_verified_at IS NOT NULL) AS emailVerified,must_change_password AS passwordChangeRequired FROM users ORDER BY id"),
            "activeVerifiedAdministrators",db.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE' AND email_verified_at IS NOT NULL AND must_change_password=FALSE",Long.class),
            "operatorMustVerify","核对待用账号、备份和管理员邮箱登录/找回演练后，由部署负责人显式切换。"));
    }
    @Transactional public void maintenanceRecover(long id,String password,String operator,String verification) {
        maintenanceAllowed();AdminInput.password(password);reference(operator);reference(verification);ledger.lock();var a=account(id);
        if(!"ACTIVE".equals(a.get("status")))throw new EmailFailure(409,"ACCOUNT_DISABLED","停用账号需先经正常管理流程启用");
        db.update("UPDATE users SET email=NULL,display_email=NULL,email_verified_at=NULL,password_hash=?,must_change_password=TRUE,recovery_until=CURRENT_TIMESTAMP(6)+INTERVAL 60 MINUTE,credential_version=credential_version+1,version=version+1 WHERE id=?",passwords.encode(password),id);
        revokeUser(id,(String)a.get("username"));
        db.update("UPDATE email_invitations SET status='REVOKED' WHERE inviter_id=? AND status='PENDING'",id);
        db.update("INSERT INTO identity_maintenance_audit(user_id,operation,operator_reference,verification_reference) VALUES(?,'ACCOUNT_RECOVERY',?,?)",id,operator,verification);
    }
    @Transactional public void invalidateRestored(String operator,String verification) {
        maintenanceAllowed();reference(operator);reference(verification);ledger.lock();
        // Keep an expired marker for recovery credentials. Clearing it to NULL would let a restored
        // temporary password fall back to ordinary legacy migration when MIGRATION_ONLY is enabled.
        db.update("UPDATE users SET credential_version=credential_version+1,version=version+1,recovery_until=CASE WHEN recovery_until IS NOT NULL THEN CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND ELSE NULL END");
        db.update("DELETE FROM persistent_logins");
        db.update("UPDATE email_actions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE consumed_at IS NULL AND revoked_at IS NULL");
        db.update("UPDATE email_invitations SET status='REVOKED' WHERE status='PENDING'");
        db.update("INSERT INTO identity_maintenance_audit(operation,operator_reference,verification_reference) VALUES('INVALIDATE_RESTORED',?,?)",operator,verification);
    }
    private void maintenanceAllowed(){if(!env.getProperty("IDENTITY_MAINTENANCE_ENABLED",Boolean.class,false) || !env.getProperty("IDENTITY_VERIFIED",Boolean.class,false))throw new EmailFailure(403,"MAINTENANCE_NOT_AUTHORIZED","维护权限与身份核实记录必须显式提供");}
    private void reference(String s){if(s==null || !s.matches("[A-Za-z0-9_-]{3,100}"))throw EmailFailure.invalid();}
    private void revokeUser(long id,String username){db.update("DELETE FROM persistent_logins WHERE username=?",username);db.update("UPDATE email_actions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL",id);}
    private boolean exists(String email){return db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Long.class,email)>0;}
    private Map<String,Object> account(long id){var rows=db.queryForList("SELECT *,recovery_until>CURRENT_TIMESTAMP(6) AS recovery_active FROM users WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw EmailFailure.token();return rows.get(0);}
    private static long number(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
    private static boolean truth(Object v){return Boolean.TRUE.equals(v) || (v instanceof Number n && n.intValue()!=0);}
    private void audit(long id,String action,String result){db.update("INSERT INTO administration_audit(actor_id,action,object_id,result,request_id,request_hash,response_json) VALUES(?,?,?,?,?,'-','{}')",id,action,Long.toString(id),result,UUID.randomUUID().toString());}
}

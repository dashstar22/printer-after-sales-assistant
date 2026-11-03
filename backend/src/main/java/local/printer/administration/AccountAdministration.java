package local.printer.administration;

import java.util.*;
import local.printer.auth.SessionUser;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class AccountAdministration {
    private final AdminLedger ledger;
    private final PasswordEncoder passwords=PasswordEncoderFactories.createDelegatingPasswordEncoder();
    public AccountAdministration(AdminLedger ledger){this.ledger=ledger;}
    public static final String SELECT="SELECT id,username,role,status,email,display_email AS displayEmail,display_name AS displayName,credential_version AS credentialVersion,must_change_password AS mustChangePassword,email_verified_at AS emailVerifiedAt,version FROM users";
    @Transactional public long initialize(String username,String password) {
        username=AdminInput.text(username,"username",64).toLowerCase(Locale.ROOT);
        if(!username.matches("[a-z0-9_]{4,64}"))throw AdminFailure.invalid("账号格式不正确");
        password=AdminInput.password(password); ledger.lock();
        if(ledger.db().queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN'",Long.class)>0)
            throw AdminFailure.conflict("ADMIN_ALREADY_INITIALIZED");
        ledger.db().update("INSERT INTO users(username,password_hash,role) VALUES(?,?,'ADMIN')",username,passwords.encode(password));
        long id=ledger.inserted();
        ledger.db().update("INSERT INTO administration_audit(actor_id,action,object_id,result,request_id,request_hash,response_json) VALUES(?,'INITIALIZE_ADMIN',?,'SUCCESS',?,'-','{}')",id,Long.toString(id),UUID.randomUUID().toString());
        return id;
    }
    @Transactional public JsonNode create(SessionUser actor,JsonNode b) {
        ledger.lock();ledger.actor(actor,true);
        throw new AdminFailure(410,"EMAIL_WORKFLOW_REQUIRED","客户须验证注册；客服须使用邮箱邀请");
    }
    public JsonNode account(long id){return ledger.node(ledger.row(SELECT+" WHERE id=?",id));}
    @Transactional public JsonNode status(SessionUser actor,long id,JsonNode b) {
        AdminInput.fields(b,"status","expectedVersion","requestId");
        return ledger.once(actor,"ACCOUNT_STATUS",Long.toString(id),b,()->{
            var row=ledger.row(SELECT+" WHERE id=? FOR UPDATE",id); ledger.version(row,b);
            String status=AdminInput.text(b,"status",16);
            if(!Set.of("ACTIVE","DISABLED").contains(status))throw AdminFailure.invalid("账号状态不正确");
            if("ADMIN".equals(row.get("role")) && "ACTIVE".equals(row.get("status")) && !Boolean.TRUE.equals(row.get("mustChangePassword")) && row.get("emailVerifiedAt")!=null && status.equals("DISABLED")
                && ledger.db().queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE' AND must_change_password=FALSE AND email_verified_at IS NOT NULL",Long.class)<=1)
                throw AdminFailure.conflict("LAST_ADMIN_PROTECTED");
            ledger.db().update("UPDATE users SET status=?,credential_version=credential_version+1,version=version+1 WHERE id=? AND version=?",status,id,AdminInput.number(b,"expectedVersion"));
            revoke((String)row.get("username"));return account(id);
        });
    }
    @Transactional public JsonNode reset(SessionUser actor,long id,JsonNode b) {
        ledger.lock();ledger.actor(actor,true);
        throw new AdminFailure(410,"EMAIL_WORKFLOW_REQUIRED","请使用本人邮箱找回；遗失邮箱需核实身份后离线恢复");
    }
    @Transactional public JsonNode change(SessionUser actor,JsonNode b) {
        AdminInput.fields(b,"currentPassword","newPassword","requestId");
        return ledger.once(actor,"PASSWORD_CHANGE",Long.toString(actor.identity().id()),b,()->{
            var row=ledger.row("SELECT username,password_hash FROM users WHERE id=? FOR UPDATE",actor.identity().id());
            String old=AdminInput.password(b,"currentPassword"),next=AdminInput.password(b,"newPassword");
            if(!passwords.matches(old,(String)row.get("password_hash")))throw new AdminFailure(400,"PASSWORD_INVALID","当前密码不正确");
            if(old.equals(next))throw AdminFailure.invalid("新密码须不同于当前密码");
            ledger.db().update("UPDATE users SET password_hash=?,must_change_password=FALSE,credential_version=credential_version+1,version=version+1 WHERE id=?",passwords.encode(next),actor.identity().id());
            revoke((String)row.get("username"));return ledger.node(Map.of("changed",true,"loginRequired",true));
        });
    }
    private void revoke(String username){
        ledger.db().update("DELETE FROM persistent_logins WHERE username=?",username);
        ledger.db().update("UPDATE email_actions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE user_id=(SELECT id FROM users WHERE username=?) AND consumed_at IS NULL AND revoked_at IS NULL",username);
    }
}

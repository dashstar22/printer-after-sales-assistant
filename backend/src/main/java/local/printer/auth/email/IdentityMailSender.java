package local.printer.auth.email;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

/** Synchronous, bounded, one attempt. No exception/message address or full link is logged. */
@Service
public class IdentityMailSender {
    private final JdbcTemplate db;
    private final JavaMailSenderImpl sender=new JavaMailSenderImpl();
    private final Semaphore slots=new Semaphore(2);
    private final boolean ready;
    private final String origin,from;
    private final int dailyCap;
    private final String binding,host;
    private final boolean tls,implicitTls,capture;
    private local.printer.operations.ControlledDispatchGate gate;
    @org.springframework.beans.factory.annotation.Autowired public void gate(local.printer.operations.ControlledDispatchGate value){gate=value;}
    public IdentityMailSender(JdbcTemplate db,Environment env) {
        this.db=db;
        String configured=env.getProperty("EMAIL_SITE_ORIGIN","");
        String senderAddress=env.getProperty("EMAIL_FROM","");
        boolean valid=false;
        try {
            var uri=URI.create(configured);
            valid="https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getRawUserInfo()==null && uri.getRawQuery()==null && uri.getRawFragment()==null
                && (uri.getRawPath()==null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"));
            EmailAddress.parse(senderAddress);
        }catch(RuntimeException invalid){valid=false;}
        origin=configured.endsWith("/")?configured.substring(0,configured.length()-1):configured;
        from=senderAddress;
        host=env.getProperty("EMAIL_SMTP_HOST","");
        capture=env.getProperty("EMAIL_CAPTURE_ONLY",Boolean.class,false);
        tls=env.getProperty("EMAIL_SMTP_STARTTLS",Boolean.class,true);
        implicitTls=env.getProperty("EMAIL_SMTP_SSL",Boolean.class,false);
        String transportBinding=String.join("\n",host,env.getProperty("EMAIL_SMTP_PORT","587"),env.getProperty("EMAIL_SMTP_USERNAME",""),from,origin,Boolean.toString(tls),Boolean.toString(capture),local.printer.operations.ControlledDispatchGate.hash(env.getProperty("EMAIL_SMTP_PASSWORD","")));
        binding=local.printer.operations.ControlledDispatchGate.hash(transportBinding+(implicitTls?"\nIMPLICIT_TLS":""));
        // Plain SMTP is restricted to explicit loopback capture, never a remote fallback.
        ready=env.getProperty("EMAIL_ENABLED",Boolean.class,false) && valid && !host.isBlank()
            && !(tls && implicitTls)
            && ((tls || implicitTls) && !capture || (capture && !tls && !implicitTls && java.util.Set.of("127.0.0.1","localhost","::1").contains(host)));
        dailyCap=Math.max(1,env.getProperty("EMAIL_DAILY_CAP",Integer.class,100));
        sender.setHost(host);sender.setPort(env.getProperty("EMAIL_SMTP_PORT",Integer.class,587));
        sender.setUsername(env.getProperty("EMAIL_SMTP_USERNAME",""));sender.setPassword(env.getProperty("EMAIL_SMTP_PASSWORD",""));
        var p=sender.getJavaMailProperties();
        p.setProperty("mail.smtp.connectiontimeout","5000");p.setProperty("mail.smtp.timeout","3000");p.setProperty("mail.smtp.writetimeout","5000");
        p.setProperty("mail.smtp.starttls.enable",Boolean.toString(tls));p.setProperty("mail.smtp.starttls.required",Boolean.toString(tls));
        p.setProperty("mail.smtp.ssl.enable",Boolean.toString(implicitTls));
        p.setProperty("mail.smtp.ssl.checkserveridentity","true");p.setProperty("mail.smtp.auth",Boolean.toString(!env.getProperty("EMAIL_SMTP_USERNAME","").isBlank()));
        p.setProperty("mail.debug","false");
        // Jakarta Mail otherwise reverse-resolves the Windows hostname while generating Message-ID,
        // outside SMTP socket timeouts. Use the validated fixed application domain, never request Host.
        p.setProperty("mail.host",valid?URI.create(configured).getHost():"localhost");
        p.setProperty("mail.from",senderAddress);
        p.setProperty("mail.smtp.localhost",valid?URI.create(configured).getHost():"localhost");
    }
    public void requireReady(){if(!ready)throw new EmailFailure(503,"EMAIL_UNAVAILABLE","暂时无法受理邮件，请稍后重试");if(gate!=null&&gate.required())try{gate.ready();}catch(local.printer.operations.ControlledDispatchGate.Denied denied){throw new EmailFailure(503,"EMAIL_UNAVAILABLE","暂时无法受理邮件，请稍后重试");}}
    public int dailyCap(){return dailyCap;}
    public void send(EmailIdentityService.Delivery delivery) {
        if(delivery==null)return;
        if(!slots.tryAcquire()){finish(delivery.sendId(),"FAILED","CONCURRENCY_LIMIT");return;}
        local.printer.operations.ControlledDispatchGate.Permit permit=null;
        try {
            var msg=new SimpleMailMessage();msg.setFrom(from);msg.setTo(delivery.email());
            String action=Map.of("REGISTER","验证注册","INVITE","接受客服邀请","RESET","重设密码","BIND","绑定邮箱").get(delivery.purpose());
            msg.setSubject("打印机售后服务："+action);
            msg.setText("打印机售后服务\n动作："+action+"\n有效分钟："+delivery.minutes()+"\n"+origin+"/app/#email-action="+delivery.purpose()+"&token="+delivery.token()+"\n请勿转发此链接。如非本人操作，请忽略。");
            if(gate!=null&&gate.required()){
                gate.transport("SMTP",host,(tls||implicitTls)&&!capture);
                permit=gate.reserve("SMTP",delivery.purpose(),delivery.email(),binding,local.printer.operations.ControlledDispatchGate.hash(msg.getText()),gate.instance()+":mail:"+delivery.sendId());
            }
            sender.send(msg);finish(delivery.sendId(),"SMTP_ACCEPTED",null);
            if(permit!=null)gate.finish(permit,"SMTP_ACCEPTED");
        }catch(local.printer.operations.ControlledDispatchGate.Denied denied){
            // If SMTP accepted before journal acknowledgement failed, preserve unknown; never retry.
            finish(delivery.sendId(),permit==null?"FAILED":"UNKNOWN",permit==null?"CONTROLLED_DISPATCH_DENIED":"CONTROLLED_JOURNAL_UNKNOWN");
        }catch(RuntimeException failure){
            // A timeout can occur after DATA acceptance. Never claim a definitive delivery failure.
            boolean timeout=timeout(failure);
            finish(delivery.sendId(),timeout?"UNKNOWN":"FAILED",timeout?"SMTP_TIMEOUT":"SMTP_REJECTED");
            if(permit!=null)try{gate.finish(permit,timeout?"UNKNOWN":"FAILED");}catch(local.printer.operations.ControlledDispatchGate.Denied unknown){/* reservation stays occupied */}
        }finally{slots.release();}
    }
    private boolean timeout(Throwable e) {
        for(Throwable c=e;c!=null;c=c.getCause()) {
            if(c instanceof java.net.SocketTimeoutException || c instanceof java.io.InterruptedIOException)return true;
            if(c instanceof org.springframework.mail.MailSendException m)for(Exception nested:m.getMessageExceptions())if(nested!=c && timeout(nested))return true;
            if(c instanceof jakarta.mail.MessagingException m && m.getNextException()!=null && m.getNextException()!=c && timeout(m.getNextException()))return true;
        }
        return false;
    }
    private void finish(long id,String status,String reason){db.update("UPDATE email_sends SET status=?,reason=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='ACCEPTED'",status,reason,id);}
}

package local.printer.auth.email;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import static org.junit.jupiter.api.Assertions.*;

class IdentityMailTransportTest {
    private IdentityMailSender configured(String host,boolean starttls,boolean ssl,boolean capture) {
        var env=new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("transport-test",Map.of(
            "EMAIL_ENABLED","true","EMAIL_SITE_ORIGIN","https://localhost:9443",
            "EMAIL_FROM","sender@example.test","EMAIL_SMTP_HOST",host,
            "EMAIL_SMTP_STARTTLS",Boolean.toString(starttls),"EMAIL_SMTP_SSL",Boolean.toString(ssl),
            "EMAIL_CAPTURE_ONLY",Boolean.toString(capture))));
        return new IdentityMailSender(new JdbcTemplate(),env);
    }
    private Object field(IdentityMailSender value,String name)throws Exception {
        var field=IdentityMailSender.class.getDeclaredField(name);field.setAccessible(true);return field.get(value);
    }
    @Test void implicitTlsRequiresNoStarttlsAndKeepsCertificateAndHostChecks()throws Exception {
        var value=configured("smtp.example.test",false,true,false);value.requireReady();
        var properties=((JavaMailSenderImpl)field(value,"sender")).getJavaMailProperties();
        assertEquals("true",properties.getProperty("mail.smtp.ssl.enable"));
        assertEquals("true",properties.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertEquals("false",properties.getProperty("mail.smtp.starttls.required"));
        assertNotEquals(field(value,"binding"),field(configured("smtp.example.test",false,false,false),"binding"));
    }
    @Test void remotePlaintextConflictingTlsAndRemoteCaptureNeverBecomeReady() {
        assertThrows(EmailFailure.class,()->configured("smtp.example.test",false,false,false).requireReady());
        assertThrows(EmailFailure.class,()->configured("smtp.example.test",true,true,false).requireReady());
        assertThrows(EmailFailure.class,()->configured("smtp.example.test",true,false,true).requireReady());
        assertThrows(EmailFailure.class,()->configured("smtp.example.test",false,true,true).requireReady());
    }
    @Test void starttlsAndExplicitLocalPlainCaptureRemainAvailable() {
        assertDoesNotThrow(()->configured("smtp.example.test",true,false,false).requireReady());
        assertDoesNotThrow(()->configured("127.0.0.1",false,false,true).requireReady());
    }
}

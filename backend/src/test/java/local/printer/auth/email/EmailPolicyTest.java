package local.printer.auth.email;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmailPolicyTest {
    @Test void normalizesOnceWithoutGuessingAliases() {
        assertEquals("a.b+tag@example.test",EmailAddress.parse("  A.B+Tag@Example.Test  ").normalized());
        assertNotEquals(EmailAddress.parse("ab@example.test").normalized(),EmailAddress.parse("a.b@example.test").normalized());
        assertNotEquals(EmailAddress.parse("a@example.test").normalized(),EmailAddress.parse("a+tag@example.test").normalized());
        assertFalse(EmailAddress.parse("secret@example.test").toString().contains("secret"));
    }
    @Test void rejectsHeaderInjectionUnicodeAndAmbiguity() {
        for(String value:new String[]{"a@例子.test","名字@example.test","a@example.test\r\nBcc:x@x.test","a@@example.test","a..b@example.test",".a@example.test","a@-example.test","a@example.test.","a@localhost","a b@example.test","a".repeat(65)+"@example.test"})
            assertThrows(EmailFailure.class,()->EmailAddress.parse(value));
    }
    @Test void ipBudgetAndConsumptionFailuresAreSeparateAndExpire() {
        MutableClock clock=new MutableClock();var limits=new EmailRateLimits(clock);
        for(int i=0;i<20;i++)limits.request("a");
        assertEquals(429,assertThrows(EmailFailure.class,()->limits.request("a")).status);
        limits.request("b");limits.consumption("a");
        for(int i=0;i<10;i++)limits.failed("a");
        assertThrows(EmailFailure.class,()->limits.consumption("a"));
        clock.now+=900001;limits.consumption("a");
        assertThrows(EmailFailure.class,()->limits.request("a"));
        clock.now+=3600001;limits.request("a");
    }
    @Test void keyCapacityFailsClosedInsteadOfEvictingAttackCounters() {
        var limits=new EmailRateLimits(new MutableClock());
        for(int i=0;i<10000;i++)limits.request("ip"+i);
        assertThrows(EmailFailure.class,()->limits.request("fresh"));
        assertDoesNotThrow(()->limits.request("ip1"));
    }
    private static final class MutableClock extends Clock {
        long now=1700000000000L;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}
        public long millis(){return now;}
    }
}

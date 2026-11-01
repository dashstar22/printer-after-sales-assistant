package local.printer.ticket;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ConfirmationTime {
    @Bean Clock confirmationClock() { return Clock.systemUTC(); }
}

package local.printer.integration;

import java.net.URI;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AutomationSettings {
    final boolean enabled;
    final Set<String> hosts, origins, targets;
    final String site;
    final int pendingHours, inactiveHours;
    AutomationSettings(Environment env) {
        enabled=env.getProperty("app.automation.enabled",Boolean.class,false);
        hosts=csv(env.getProperty("app.automation.allowed-hosts",""));
        origins=csv(env.getProperty("app.automation.allowed-origins",""));
        targets=csv(env.getProperty("app.automation.enabled-targets",""));
        site=env.getProperty("app.automation.site-url","http://localhost:8080");
        URI uri=URI.create(site);
        if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
            || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException("AUTOMATION_SITE_INVALID");
        pendingHours=env.getProperty("app.automation.pending-hours",Integer.class,2);
        inactiveHours=env.getProperty("app.automation.inactive-hours",Integer.class,24);
        if(pendingHours<1 || pendingHours>720 || inactiveHours<1 || inactiveHours>720)throw new IllegalArgumentException("AUTOMATION_THRESHOLD_INVALID");
    }
    static Set<String> csv(String value){return new HashSet<>(Arrays.stream(value.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList());}
    String ticketLink(){return site.replaceAll("/$","")+"/app/";}
}

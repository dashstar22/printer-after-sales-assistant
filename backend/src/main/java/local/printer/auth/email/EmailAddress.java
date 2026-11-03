package local.printer.auth.email;

import java.util.Locale;

/** One deliberately narrow ASCII login identifier policy. Never fold plus tags or dots. */
public record EmailAddress(String normalized,String display) {
    public static EmailAddress parse(String value) {
        if(value==null)throw EmailFailure.invalid();
        String display=value.strip();
        if(display.length()>254 || !display.matches("[\\x21-\\x7e]+"))throw EmailFailure.invalid();
        int at=display.indexOf('@');
        if(at<1 || at>64 || at!=display.lastIndexOf('@'))throw EmailFailure.invalid();
        String local=display.substring(0,at),domain=display.substring(at+1);
        if(!local.matches("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+") || local.startsWith(".") || local.endsWith(".") || local.contains(".."))throw EmailFailure.invalid();
        if(domain.length()>253 || !domain.contains("."))throw EmailFailure.invalid();
        for(String label:domain.split("\\.",-1))if(label.length()>63 || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))throw EmailFailure.invalid();
        return new EmailAddress(display.toLowerCase(Locale.ROOT),display);
    }
    @Override public String toString(){return "EmailAddress[REDACTED]";}
}

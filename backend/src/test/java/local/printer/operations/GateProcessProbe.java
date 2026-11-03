package local.printer.operations;
import org.springframework.mock.env.MockEnvironment;
/** Child exits without finish to exercise OS file lock + durable reservation across processes. */
public class GateProcessProbe {
    public static void main(String[] args){var env=new MockEnvironment().withProperty("CONTROLLED_DISPATCH_REQUIRED","true").withProperty("CONTROLLED_DISPATCH_POLICY",args[0]).withProperty("CONTROLLED_DISPATCH_LEDGER",args[1]).withProperty("CONTROLLED_DISPATCH_POLICY_SHA256",args[2]).withProperty("CONTROLLED_DISPATCH_INSTANCE","A");
        new ControlledDispatchGate(env).reserve("SMTP","BIND","a@dispatch.invalid",ControlledDispatchGateTest.BINDING,ControlledDispatchGateTest.INPUT,"A:process");}
}

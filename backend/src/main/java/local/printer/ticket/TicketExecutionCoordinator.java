package local.printer.ticket;

import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import local.printer.assistant.AssistantAudit;

/** 页面确认后的确定性入口；普通表单与咨询链路复用同一个适配器和业务事务。 */
@Service
public class TicketExecutionCoordinator {
    private final CreateServiceTicketTool tool;
    public TicketExecutionCoordinator(CreateServiceTicketTool tool) { this.tool = tool; }
    public TicketOperationService.Result execute(long customerId, String operationId, TicketOperationService.Input input) {
        long started=AssistantAudit.clock();String status="FAILED";
        try {
        try {var result=tool.executeConfirmed(customerId, operationId, input);status="SUCCEEDED";return result;}
        catch(DataAccessException uncertain){
            // A connection failure is not proof of rollback. Query the ORIGINAL binding once; never retry a write.
            try {var recovered=tool.recoverConfirmed(customerId,operationId,input);if(recovered!=null){status="SUCCEEDED";return recovered;}}
            catch(RuntimeException readFailed){ /* Preserve the fixed original error contract; caller retains original ID. */ }
            throw uncertain;
        }
        } finally {AssistantAudit.tool("createServiceTicket",status,started,"SERVER_COORDINATOR");}
    }
}

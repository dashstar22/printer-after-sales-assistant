package local.printer.ticket;

import org.springframework.stereotype.Service;

/** T027 工具适配器：模型无确认上下文；受保护的页面动作才可调用确定性写路径。 */
@Service
public class CreateServiceTicketTool {
    private final TicketOperationService operations;
    public CreateServiceTicketTool(TicketOperationService operations) { this.operations = operations; }

    public String withoutPageConfirmation() { return "{\"status\":\"PAGE_CONFIRMATION_REQUIRED\",\"ticketCreated\":false}"; }

    // 不注册为模型工具。参数由CUSTOMER/CSRF保护的HTTP动作注入；业务服务再次验证持久化确认。
    public TicketOperationService.Result executeConfirmed(long customerId, String operationId, TicketOperationService.Input input) {
        return operations.execute(customerId, operationId, input);
    }
    /** Read-only recovery after an uncertain write. Never creates a confirmation or another operation ID. */
    public TicketOperationService.Result recoverConfirmed(long customerId,String operationId,TicketOperationService.Input input){
        var result=operations.detail(customerId,operationId);
        if(!result.confirmationId().equals(input.confirmationId())||result.draftId()!=input.draftId()||result.draftVersion()!=input.expectedVersion())
            throw new ConfirmationService.Conflict();
        return "SUCCEEDED".equals(result.status())?new TicketOperationService.Result(result,false):null;
    }
}

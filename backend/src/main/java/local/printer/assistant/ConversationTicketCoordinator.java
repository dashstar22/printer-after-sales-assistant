package local.printer.assistant;

import local.printer.order.OrderService;
import local.printer.ticket.*;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** T027：页面动作确定性建单；模型只生成可选的事后提示，不参与授权或决定业务结果。 */
@Service
public class ConversationTicketCoordinator {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConversationMapper mapper;
    private final OrderService orders;
    private final DraftService drafts;
    private final TicketExecutionCoordinator execution;
    private final AssistantConversationService assistant;
    private final TransactionTemplate write;
    public ConversationTicketCoordinator(ConversationMapper mapper, OrderService orders, DraftService drafts,
            TicketExecutionCoordinator execution, AssistantConversationService assistant, PlatformTransactionManager tm) {
        this.mapper=mapper;this.orders=orders;this.drafts=drafts;this.execution=execution;this.assistant=assistant;this.write=new TransactionTemplate(tm);
    }
    public record Result(ConversationResponse.Reply response, String followupStatus, String followupText) {}
    private record Checkpoint(ConversationResponse.Reply reply, boolean first) {}

    public Result execute(long customer, String conversation, String operation, TicketOperationService.Input input) {
        ConversationService.uuid(conversation);
        var owned=mapper.owned(conversation,customer);
        if(owned==null)throw new ConversationService.NotFound();
        orders.detail(customer,owned.orderId());
        var message=mapper.preparedDraft(conversation,customer,input.draftId());
        if(message==null)throw new ConfirmationService.Conflict();
        var draft=drafts.detail(customer,input.draftId());
        if(draft.orderId()!=owned.orderId())throw new ConfirmationService.Conflict();
        // 关键写入独立提交；身份、版本、内容哈希、过期、幂等及原子事件均由原服务校验。
        var committed=execution.execute(customer,operation,input).response();
        var previous=JSON.readValue(message.responseJson(),ConversationResponse.Reply.class);
        var canonical=reply(previous,draft,committed);
        Checkpoint checkpoint;
        try {
            checkpoint=write.execute(tx -> {
                if(mapper.lockOwned(conversation,customer)==null)throw new ConversationService.NotFound();
                var current=mapper.preparedDraft(conversation,customer,input.draftId());
                var stored=JSON.readValue(current.responseJson(),ConversationResponse.Reply.class);
                boolean first=stored.operation()==null;
                var saved=reply(stored,draft,committed);
                if(mapper.complete(conversation,customer,current.turnId(),saved.text(),JSON.writeValueAsString(saved))!=1)
                    throw new ConversationService.Conflict();
                return new Checkpoint(saved,first);
            });
        } catch(RuntimeException checkpointFailure) {
            // 工单已提交，不能以消息检查点失败冒充未建单；原操作GET仍是恢复依据。
            trace(conversation,"CHECKPOINT_UNAVAILABLE");
            return new Result(canonical,"CHECKPOINT_UNAVAILABLE",null);
        }
        var followup=checkpoint.first()?assistant.ticketFollowup(committed):new AssistantConversationService.Followup("NOT_REQUESTED_ON_REPLAY",null);
        trace(conversation,followup.status());
        return new Result(checkpoint.reply(),followup.status(),followup.text());
    }
    private static ConversationResponse.Reply reply(ConversationResponse.Reply old, DraftResponse draft, TicketOperationResponse result) {
        return new ConversationResponse.Reply("SERVER_COORDINATOR","TICKET_CREATED",
            "报修已提交，工单编号："+result.ticket().ticketNo()+"。可在工单列表查看进度。",old.citations(),draft,result);
    }
    private static void trace(String conversation,String status) {
        var row=new java.util.LinkedHashMap<String,Object>();row.put("conversationId",conversation);row.put("tool","createServiceTicket");
        row.put("status","SUCCEEDED");row.put("followupStatus",AssistantAudit.code(status));AssistantAudit.decorate(row);
        LoggerFactory.getLogger(ConversationTicketCoordinator.class).info("T027_TRACE {}",
            JSON.writeValueAsString(row));
    }
}

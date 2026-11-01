package local.printer.assistant;

import java.time.Instant;
import java.util.List;
import local.printer.knowledge.KnowledgeRetrievalService;
import local.printer.order.OrderResponse;
import local.printer.ticket.DraftResponse;
import local.printer.ticket.TicketOperationResponse;

/** HTTP whitelist. A stored acknowledgement is explicitly distinguishable from an AI answer. */
public final class ConversationResponse {
    private ConversationResponse() {}
    public record Reply(String mode, String status, String text,
                        List<KnowledgeRetrievalService.Source> citations, DraftResponse draft,
                        TicketOperationResponse operation) {}
    public record Message(long id, String turnId, String role, String content, Reply response, Instant createdAt) {
        @Override public String toString() { return "Message[REDACTED]"; }
    }
    public record Page(List<Message> items, int page, int size, long total, long totalPages) {}
    public record Detail(String id, long orderId, OrderResponse order, Instant createdAt, String notice, Page messages) {}
    public record Turn(String conversationId, String turnId, boolean replayed, Message userMessage, Message assistantMessage) {}
}

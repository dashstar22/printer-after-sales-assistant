package local.printer.assistant;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import local.printer.order.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConversationService {
    private final ConversationMapper mapper;
    private final OrderService orders;
    private final ObjectMapper json = new ObjectMapper();
    public ConversationService(ConversationMapper mapper, OrderService orders) { this.mapper = mapper; this.orders = orders; }
    public static class NotFound extends RuntimeException {}
    public static class InvalidInput extends RuntimeException {}
    public static class Conflict extends RuntimeException {}

    @Transactional
    public ConversationResponse.Detail create(long customerId, long orderId) {
        if (orderId < 1) throw new InvalidInput();
        orders.detail(customerId, orderId);
        String id = UUID.randomUUID().toString();
        if (mapper.insertConversation(id, customerId, orderId) != 1) throw new Conflict();
        return detail(customerId, id, 1, 20);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConversationResponse.Detail detail(long customerId, String id, int page, int size) {
        uuid(id);
        if (page < 1 || size < 1 || size > 100) throw new InvalidInput();
        var row = mapper.owned(id, customerId);
        if (row == null) throw new NotFound();
        var order = orders.detail(customerId, row.orderId());
        long total = mapper.count(id, customerId);
        var items = mapper.messages(id, customerId, ((long) page - 1) * size, size).stream().map(this::message).toList();
        return new ConversationResponse.Detail(row.id(), row.orderId(), order, row.createdAt().toInstant(ZoneOffset.UTC),
            "当前咨询仅关联此订单；更换打印机设备请新建咨询，原消息保留在原咨询。",
            new ConversationResponse.Page(items, page, size, total, total == 0 ? 0 : (total - 1) / size + 1));
    }

    @Transactional
    public ConversationResponse.Turn submit(long customerId, String id, String turnId, String content) {
        uuid(id); uuid(turnId);
        content = content(content);
        var conversation = mapper.lockOwned(id, customerId);
        if (conversation == null) throw new NotFound();
        orders.detail(customerId, conversation.orderId());
        var existing = mapper.turn(id, customerId, turnId);
        if (!existing.isEmpty()) {
            if (existing.size() != 2 || !existing.get(0).content().equals(content)) throw new Conflict();
            return turn(id, turnId, true, existing);
        }
        // T025 has no model/tool path. Persist this honest protocol acknowledgement atomically with the submitted message.
        var reply = new ConversationResponse.Reply("PERSISTENCE_ONLY", "ASSISTANT_NOT_CONNECTED",
            "消息已保存。助手当前未启用，暂不能提供排障建议或执行操作；可使用普通报修入口。", List.of(), null, null);
        if (mapper.insertMessage(id, customerId, turnId, "USER", content, null) != 1
            || mapper.insertMessage(id, customerId, turnId, "ASSISTANT", reply.text(), json.writeValueAsString(reply)) != 1) {
            throw new Conflict();
        }
        return turn(id, turnId, false, mapper.turn(id, customerId, turnId));
    }

    @Transactional(readOnly = true)
    public ConversationResponse.Turn savedTurn(long customerId, String id, String turnId, boolean replayed) {
        if (mapper.owned(id, customerId) == null) throw new NotFound();
        return turn(id, turnId, replayed, mapper.turn(id, customerId, turnId));
    }

    private ConversationResponse.Turn turn(String id, String turnId, boolean replayed, List<ConversationMapper.Message> rows) {
        if (rows.size() != 2 || !"USER".equals(rows.get(0).role()) || !"ASSISTANT".equals(rows.get(1).role())) throw new Conflict();
        return new ConversationResponse.Turn(id, turnId, replayed, message(rows.get(0)), message(rows.get(1)));
    }
    private ConversationResponse.Message message(ConversationMapper.Message row) {
        var reply = row.responseJson() == null ? null : json.readValue(row.responseJson(), ConversationResponse.Reply.class);
        return new ConversationResponse.Message(row.id(), row.turnId(), row.role(), row.content(), reply, row.createdAt().toInstant(ZoneOffset.UTC));
    }
    static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new InvalidInput();
        return value;
    }
    static String content(String value) {
        if (value == null) throw new InvalidInput();
        // Validate before trimming, so unpaired surrogates and NUL cannot enter MySQL or be silently replaced.
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            if ((cp >= 0xD800 && cp <= 0xDFFF) || (Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t')) throw new InvalidInput();
            i += Character.charCount(cp);
        }
        int start = 0, end = value.length();
        while (start < end && space(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && space(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        String normalized = value.substring(start, end);
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 4000) throw new InvalidInput();
        return normalized;
    }
    private static boolean space(int cp) { return Character.isWhitespace(cp) || Character.isSpaceChar(cp); }
}

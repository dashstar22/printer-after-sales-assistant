package local.printer.ticket;

import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 两个执行入口共享原有精确字段及类型契约；客户端不能提供身份或重新生成的业务正文。 */
public final class TicketOperationInput {
    private TicketOperationInput() {}
    public static TicketOperationService.Input parse(JsonNode body) {
        if (body == null || !body.isObject() || !body.propertyNames().equals(Set.of("confirmationId", "draftId", "expectedVersion")))
            throw new DraftService.InvalidInput();
        var confirmation = body.get("confirmationId");
        if (!confirmation.isString()) throw new DraftService.InvalidInput();
        return new TicketOperationService.Input(uuid(confirmation.asString()), number(body.get("draftId"), 1), number(body.get("expectedVersion"), 0));
    }
    private static long number(JsonNode value, long min) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < min) throw new DraftService.InvalidInput();
        return value.asLong();
    }
    public static String uuid(String value) {
        try { if (UUID.fromString(value).toString().equals(value)) return value; }
        catch (IllegalArgumentException invalid) { /* 统一参数错误 */ }
        throw new DraftService.InvalidInput();
    }
}

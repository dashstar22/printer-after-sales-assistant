package local.printer.ticket;

import local.printer.order.OrderResponse;

/** 仅包含客户核对草稿所需字段，不暴露持久化行或确认结果。 */
public record DraftResponse(long id, long orderId, String symptom, String extra, String contact,
                            String contactMethod, long version, OrderResponse order) {
    @Override public String toString() { return "DraftResponse[REDACTED]"; }
}

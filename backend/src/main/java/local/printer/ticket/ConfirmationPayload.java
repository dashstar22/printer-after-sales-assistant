package local.printer.ticket;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import local.printer.ticket.TicketRows.Draft;

/** Canonical v1 payload; never use client hashes or model-generated repair text. */
final class ConfirmationPayload {
    private ConfirmationPayload() {}

    static String hash(Draft draft) {
        if (draft.id() == null || draft.id() < 1 || draft.customerId() < 1 || draft.orderId() < 1 || draft.version() < 0)
            throw new DraftService.InvalidInput();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{"repair-confirmation:v1", Long.toString(draft.id()),
                    Long.toString(draft.customerId()), Long.toString(draft.orderId()), Long.toString(draft.version()),
                    DraftService.text(draft.symptom(), 2000), DraftService.text(draft.extra(), 2000),
                    DraftService.text(draft.contact(), 100), DraftService.text(draft.contactMethod(), 200)}) {
                var bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}

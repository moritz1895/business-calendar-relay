package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.Objects;

/**
 * Everything a {@link BlockerSink} adapter needs to build and send one iMIP MIME
 * message, without the orchestration layer knowing MIME structure.
 *
 * @param icsText        the rendered {@code BEGIN:VCALENDAR...END:VCALENDAR} text
 * @param method         the iMIP method, agreeing with {@code icsText}'s {@code METHOD:} line
 * @param fromAddress    the sending identity, matched exactly against {@code From}/envelope-from
 * @param replyToAddress the organizer's human address, set as {@code Reply-To}
 * @param toAddress      the business Outlook mailbox the blocker mail goes to
 */
public record BlockerMail(
        String icsText, BlockerMailMethod method, String fromAddress, String replyToAddress, String toAddress) {

    public BlockerMail {
        Objects.requireNonNull(icsText, "icsText must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(fromAddress, "fromAddress must not be null");
        Objects.requireNonNull(replyToAddress, "replyToAddress must not be null");
        Objects.requireNonNull(toAddress, "toAddress must not be null");

        if (icsText.isBlank()) {
            throw new IllegalArgumentException("icsText must not be blank");
        }
        if (!fromAddress.contains("@")) {
            throw new IllegalArgumentException("fromAddress must be a valid mailto address");
        }
        if (!replyToAddress.contains("@")) {
            throw new IllegalArgumentException("replyToAddress must be a valid mailto address");
        }
        if (!toAddress.contains("@")) {
            throw new IllegalArgumentException("toAddress must be a valid mailto address");
        }
    }
}

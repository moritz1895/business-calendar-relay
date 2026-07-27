package ms.rohde.businesscalendarrelay.ports.outbound;

/**
 * The iMIP method a {@link BlockerMail} represents, mirroring the rendered ICS text's
 * {@code METHOD:} line so the adapter can set the same value in the MIME
 * {@code Content-Type: text/calendar; method=...} parameter.
 */
public enum BlockerMailMethod {
    REQUEST,
    CANCEL
}

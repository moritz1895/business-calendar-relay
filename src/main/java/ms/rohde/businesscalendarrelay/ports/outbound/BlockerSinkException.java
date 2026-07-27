package ms.rohde.businesscalendarrelay.ports.outbound;

/**
 * Thrown by {@link BlockerSink#send(BlockerMail)} when one iMIP message could not be
 * sent. A single {@code send} call either fully succeeds or throws — there is no
 * partial-success return value to inspect.
 */
public class BlockerSinkException extends RuntimeException {

    public BlockerSinkException(String message) {
        super(message);
    }

    public BlockerSinkException(String message, Throwable cause) {
        super(message, cause);
    }
}

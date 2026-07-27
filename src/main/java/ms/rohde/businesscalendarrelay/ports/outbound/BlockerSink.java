package ms.rohde.businesscalendarrelay.ports.outbound;

import ms.rohde.hexagonalarch.annotations.InfrastructureServicePort;

/**
 * Sends rendered iMIP messages to the business mailbox.
 *
 * <p>One configured instance, shared across all source calendars.
 */
@InfrastructureServicePort
public interface BlockerSink {

    /**
     * Sends one rendered iMIP message.
     *
     * @throws BlockerSinkException if the message could not be sent
     */
    void send(BlockerMail mail);
}

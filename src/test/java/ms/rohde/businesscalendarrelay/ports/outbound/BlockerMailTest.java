package ms.rohde.businesscalendarrelay.ports.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BlockerMailTest {

    private static final String ICS_TEXT = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n";

    @Test
    void create_givenValidData_thenBlockerMailIsCreated() {
        var mail = new BlockerMail(
                ICS_TEXT, BlockerMailMethod.REQUEST, "relay@example.com", "organizer@example.com", "business@example.com");

        assertThat(mail.icsText()).isEqualTo(ICS_TEXT);
        assertThat(mail.method()).isEqualTo(BlockerMailMethod.REQUEST);
        assertThat(mail.fromAddress()).isEqualTo("relay@example.com");
        assertThat(mail.replyToAddress()).isEqualTo("organizer@example.com");
        assertThat(mail.toAddress()).isEqualTo("business@example.com");
    }

    @Test
    void create_givenBlankIcsText_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new BlockerMail(
                        "  ", BlockerMailMethod.REQUEST, "relay@example.com", "organizer@example.com", "business@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenFromAddressWithoutAtSign_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new BlockerMail(
                        ICS_TEXT, BlockerMailMethod.REQUEST, "not-an-email", "organizer@example.com", "business@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenReplyToAddressWithoutAtSign_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new BlockerMail(
                        ICS_TEXT, BlockerMailMethod.REQUEST, "relay@example.com", "not-an-email", "business@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenToAddressWithoutAtSign_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new BlockerMail(
                        ICS_TEXT, BlockerMailMethod.REQUEST, "relay@example.com", "organizer@example.com", "not-an-email"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

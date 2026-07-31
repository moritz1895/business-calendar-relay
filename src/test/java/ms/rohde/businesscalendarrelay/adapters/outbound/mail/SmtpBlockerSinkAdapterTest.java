package ms.rohde.businesscalendarrelay.adapters.outbound.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerMail;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerMailMethod;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSinkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Pins the exact MIME structure {@link SmtpBlockerSinkAdapter} must build, per the
 * Outlook-verified structural findings in the project {@code CLAUDE.md}: a
 * {@code multipart/mixed} message whose {@code text/calendar} part is a sibling of
 * (not nested inside) the {@code multipart/alternative} part, with the exact
 * {@code Content-Type}/{@code Content-Transfer-Encoding}/{@code Content-Disposition}
 * headers Outlook needs to render an invitation card instead of a file attachment.
 */
@ExtendWith(MockitoExtension.class)
class SmtpBlockerSinkAdapterTest {

    private static final String ICS_TEXT = "BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR\r\n";
    private static final String FROM_ADDRESS = "relay@example.com";
    private static final String REPLY_TO_ADDRESS = "organizer@example.com";
    private static final String TO_ADDRESS = "business@example.com";

    @Mock
    private JavaMailSender mailSender;

    private SmtpBlockerSinkAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SmtpBlockerSinkAdapter(mailSender);
    }

    private static BlockerMail blockerMail(BlockerMailMethod method) {
        return new BlockerMail(ICS_TEXT, method, FROM_ADDRESS, REPLY_TO_ADDRESS, TO_ADDRESS);
    }

    private static MimeMessage newMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    private MimeMessage sendAndCapture(BlockerMail mail) throws MessagingException {
        var mimeMessage = newMimeMessage();
        given(mailSender.createMimeMessage()).willReturn(mimeMessage);

        adapter.send(mail);

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        then(mailSender).should().send(captor.capture());
        return captor.getValue();
    }

    @Test
    void send_givenRequestMail_thenTopLevelContentIsMultipartMixedWithAlternativeAndCalendarSiblingParts()
            throws MessagingException, IOException {
        var sent = sendAndCapture(blockerMail(BlockerMailMethod.REQUEST));

        assertThat(sent.getContentType()).startsWith("multipart/mixed");

        var mixed = (MimeMultipart) sent.getContent();
        assertThat(mixed.getCount()).isEqualTo(2);
        assertThat(mixed.getBodyPart(0).getContentType()).startsWith("multipart/alternative");
        assertThat(mixed.getBodyPart(1).getContentType()).startsWith("text/calendar");
    }

    @Test
    void send_givenRequestMail_thenAlternativePartContainsPlainTextThenHtmlSiblingsOnly()
            throws MessagingException, IOException {
        var sent = sendAndCapture(blockerMail(BlockerMailMethod.REQUEST));

        var mixed = (MimeMultipart) sent.getContent();
        var alternative = (MimeMultipart) mixed.getBodyPart(0).getContent();

        assertThat(alternative.getCount()).isEqualTo(2);
        assertThat(alternative.getBodyPart(0).getContentType()).startsWith("text/plain");
        assertThat(alternative.getBodyPart(1).getContentType()).startsWith("text/html");
        assertThat((String) alternative.getBodyPart(0).getContent()).isNotBlank();
        assertThat((String) alternative.getBodyPart(1).getContent()).isNotBlank();
    }

    @Test
    void send_givenRequestMail_thenCalendarPartHasExactContentTypeTransferEncodingAndDisposition()
            throws MessagingException, IOException {
        var sent = sendAndCapture(blockerMail(BlockerMailMethod.REQUEST));

        var mixed = (MimeMultipart) sent.getContent();
        var calendarPart = mixed.getBodyPart(1);

        assertThat(calendarPart.getContentType())
                .isEqualTo("text/calendar; method=REQUEST; charset=\"utf-8\"; name=event.ics");
        assertThat(calendarPart.getHeader("Content-Transfer-Encoding")).containsExactly("base64");
        assertThat(calendarPart.getHeader("Content-Disposition"))
                .containsExactly("inline; name=event.ics; filename=event.ics");
    }

    @Test
    void send_givenCancelMail_thenCalendarPartContentTypeMethodParameterIsCancel()
            throws MessagingException, IOException {
        var sent = sendAndCapture(blockerMail(BlockerMailMethod.CANCEL));

        var mixed = (MimeMultipart) sent.getContent();
        var calendarPart = mixed.getBodyPart(1);

        assertThat(calendarPart.getContentType())
                .isEqualTo("text/calendar; method=CANCEL; charset=\"utf-8\"; name=event.ics");
    }

    @Test
    void send_givenMail_thenCalendarPartBodyDecodesBackToOriginalIcsText() throws MessagingException, IOException {
        var sent = sendAndCapture(blockerMail(BlockerMailMethod.REQUEST));

        var mixed = (MimeMultipart) sent.getContent();
        var calendarPart = mixed.getBodyPart(1);

        var decoded = new String(calendarPart.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(ICS_TEXT);
    }

    @Test
    void send_givenMail_thenFromReplyToAndToAddressesAreSetExactlyWithNoSeparateSenderHeader()
            throws MessagingException {
        var sent = sendAndCapture(blockerMail(BlockerMailMethod.REQUEST));

        assertThat(sent.getFrom()).extracting(Object::toString).containsExactly(FROM_ADDRESS);
        assertThat(sent.getReplyTo()).extracting(Object::toString).containsExactly(REPLY_TO_ADDRESS);
        assertThat(sent.getRecipients(Message.RecipientType.TO))
                .extracting(Object::toString)
                .containsExactly(TO_ADDRESS);
        assertThat(sent.getHeader("Sender")).isNull();
    }

    @Test
    void send_givenMail_thenSubjectMatchesImipCalendarRenderersTitlelessSummaryLiteral() throws MessagingException {
        var sent = sendAndCapture(blockerMail(BlockerMailMethod.REQUEST));

        // Pinned, not just "non-blank": Outlook's calendar grid displays this Subject header
        // as the appointment's title, not the ICS SUMMARY property, so the two must be kept
        // in sync (see ImipCalendarRenderer's "SUMMARY:Privater Blocker" literal) -- a
        // mismatch here previously surfaced as a wrong-but-non-obviously-wrong appointment
        // title in a real mailbox, silently, with no test catching it.
        assertThat(sent.getSubject()).isEqualTo("Privater Blocker");
    }

    @Test
    void send_givenMailSenderSendThrowsMailException_thenWrapsIntoBlockerSinkExceptionWithoutSwallowingCause() {
        var mimeMessage = newMimeMessage();
        given(mailSender.createMimeMessage()).willReturn(mimeMessage);
        var cause = new MailSendException("smtp connection refused");
        willThrow(cause).given(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> adapter.send(blockerMail(BlockerMailMethod.REQUEST)))
                .isInstanceOf(BlockerSinkException.class)
                .hasCause(cause);
    }

    @Test
    void send_givenMailSenderCreateMimeMessageThrowsMailException_thenWrapsIntoBlockerSinkExceptionWithoutSwallowingCause() {
        var cause = new MailPreparationException("could not create mime message");
        given(mailSender.createMimeMessage()).willThrow(cause);

        assertThatThrownBy(() -> adapter.send(blockerMail(BlockerMailMethod.REQUEST)))
                .isInstanceOf(BlockerSinkException.class)
                .hasCause(cause);
    }

    @Test
    void send_givenFromAddressThatFailsStrictInternetAddressSyntax_thenWrapsMessagingExceptionIntoBlockerSinkException() {
        var mimeMessage = newMimeMessage();
        given(mailSender.createMimeMessage()).willReturn(mimeMessage);
        var invalidFromAddress = new BlockerMail(
                ICS_TEXT, BlockerMailMethod.REQUEST, "john doe@example.com", REPLY_TO_ADDRESS, TO_ADDRESS);

        assertThatThrownBy(() -> adapter.send(invalidFromAddress))
                .isInstanceOf(BlockerSinkException.class)
                .hasCauseInstanceOf(MessagingException.class);

        then(mailSender).should(org.mockito.Mockito.never()).send(any(MimeMessage.class));
    }
}

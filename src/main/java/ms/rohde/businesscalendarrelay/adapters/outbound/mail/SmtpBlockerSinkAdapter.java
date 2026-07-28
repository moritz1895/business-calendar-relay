package ms.rohde.businesscalendarrelay.adapters.outbound.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.nio.charset.StandardCharsets;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerMail;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSink;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSinkException;
import ms.rohde.hexagonalarch.annotations.InfrastructureServiceAdapter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends {@link BlockerMail} as iMIP {@code text/calendar} mail over SMTP.
 *
 * <p>Builds a {@code multipart/mixed} message whose {@code text/calendar} part is a
 * sibling of a {@code multipart/alternative} (plain text + HTML) part, not nested
 * inside it. This exact sibling layout, together with an {@code inline} disposition
 * carrying both {@code name=} and {@code filename=}, is what makes Outlook render an
 * invitation card instead of a file attachment. See the project {@code CLAUDE.md} for
 * the Outlook-verified structural findings this adapter reproduces.
 *
 * <p>{@code From} is set once, on the message, and no {@code Sender} header is ever
 * set, so the SMTP envelope-from used by the transport matches {@code From} exactly.
 */
@InfrastructureServiceAdapter
public final class SmtpBlockerSinkAdapter implements BlockerSink {

    private static final Logger LOG = LogManager.getLogger(SmtpBlockerSinkAdapter.class);

    private static final String SUBJECT = "Kalenderaktualisierung";

    private static final String ICS_FILE_NAME = "event.ics";

    private static final String PLAIN_TEXT_BODY = "Diese Nachricht enthaelt eine Kalender-Einladung.";

    private static final String HTML_BODY =
            "<html><body><p>Diese Nachricht enthaelt eine Kalender-Einladung.</p></body></html>";

    private final JavaMailSender mailSender;

    public SmtpBlockerSinkAdapter(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void send(BlockerMail mail) {
        try {
            var message = mailSender.createMimeMessage();
            message.setFrom(new InternetAddress(mail.fromAddress()));
            message.setReplyTo(new InternetAddress[] {new InternetAddress(mail.replyToAddress())});
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(mail.toAddress()));
            message.setSubject(SUBJECT, StandardCharsets.UTF_8.name());

            var mixed = new MimeMultipart("mixed");
            mixed.addBodyPart(alternativeBodyPart());
            mixed.addBodyPart(calendarBodyPart(mail));

            message.setContent(mixed);
            message.saveChanges();

            mailSender.send(message);
            LOG.info("Sent iMIP {} mail to {}", mail.method(), mail.toAddress());
        } catch (MessagingException | MailException e) {
            throw new BlockerSinkException(
                    "Failed to send iMIP " + mail.method() + " mail to " + mail.toAddress(), e);
        }
    }

    private MimeBodyPart alternativeBodyPart() throws MessagingException {
        var textPart = new MimeBodyPart();
        textPart.setText(PLAIN_TEXT_BODY, StandardCharsets.UTF_8.name(), "plain");

        var htmlPart = new MimeBodyPart();
        htmlPart.setText(HTML_BODY, StandardCharsets.UTF_8.name(), "html");

        var alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textPart);
        alternative.addBodyPart(htmlPart);

        var alternativePart = new MimeBodyPart();
        alternativePart.setContent(alternative);
        return alternativePart;
    }

    private MimeBodyPart calendarBodyPart(BlockerMail mail) throws MessagingException {
        var contentType = "text/calendar; method=" + mail.method() + "; charset=\"utf-8\"; name=" + ICS_FILE_NAME;
        var dataSource = new ByteArrayDataSource(mail.icsText().getBytes(StandardCharsets.UTF_8), contentType);

        var calendarPart = new MimeBodyPart();
        calendarPart.setDataHandler(new DataHandler(dataSource));
        calendarPart.setHeader("Content-Type", contentType);
        calendarPart.setHeader("Content-Transfer-Encoding", "base64");
        calendarPart.setHeader(
                "Content-Disposition", "inline; name=" + ICS_FILE_NAME + "; filename=" + ICS_FILE_NAME);
        return calendarPart;
    }
}

package com.commerceflow.notificationservice.channel;

import java.io.UnsupportedEncodingException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.commerceflow.notificationservice.config.NotificationProperties;
import com.commerceflow.notificationservice.entity.Notification;
import com.commerceflow.notificationservice.entity.NotificationChannel;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Real email, over SMTP.
 *
 * <p>Declares a higher precedence than {@link LoggingNotificationSender}, so once
 * {@code commerceflow.notification.email.enabled} is on this takes over the EMAIL channel and the
 * logging sender falls back to everything else. Nothing in the saga changes: it still asks for a
 * notification and still gets exactly one reply either way.
 *
 * <p>SMTP on purpose rather than a vendor SDK. The same adapter drives Gmail, Mailtrap, SES,
 * SendGrid or an in-house relay, and moving between them is four config lines — which is worth
 * more here than the extra features any one vendor's API would add.
 *
 * <h2>Why the timeouts are short</h2>
 *
 * <p>Delivery happens inside the transaction that writes the notification row and the
 * {@code notification.sent} reply, because those three have to commit together — a message the
 * customer received but the platform has no record of is worse than one that failed cleanly.
 *
 * <p>The cost is that a hanging SMTP server holds a database connection open. So the timeouts in
 * {@code application.yml} are deliberately tight (5 seconds each): a slow mail server should
 * produce a failed notification, not a slow order pipeline. A failure here is recorded and the
 * saga still terminates, so the blast radius of an SMTP outage is undelivered mail — not stuck
 * orders.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "commerceflow.notification.email", name = "enabled",
        havingValue = "true")
public class EmailNotificationSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.EMAIL;
    }

    @Override
    public void send(Notification notification) {
        NotificationProperties.Email config = properties.getEmail();

        requireDeliverableAddress(notification.getRecipient());

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(config.getFrom(), config.getFromName());
            helper.setTo(notification.getRecipient());
            helper.setSubject(notification.getSubject());
            // Plain text: the templates are plain text, and a message that renders identically in
            // every client beats one that needs a rendering engine to read.
            helper.setText(notification.getContent(), false);

            if (config.getReplyTo() != null && !config.getReplyTo().isBlank()) {
                helper.setReplyTo(config.getReplyTo());
            }

            mailSender.send(message);

            log.debug("Delivered {} to {} over SMTP", notification.getType(),
                    notification.getRecipient());

        } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
            // Translated rather than propagated: the caller records a FAILED notification and
            // still answers the saga, so an SMTP problem never leaves an order looking unfinished.
            throw new NotificationDeliveryException(
                    "SMTP delivery failed: " + rootReason(ex), ex);
        }
    }

    /**
     * Rejects an address the mail server could only bounce.
     *
     * <p>Jakarta Mail parses leniently by default, so a value like {@code not-an-address} is
     * accepted, handed to the SMTP server and bounced asynchronously — hours later, to a mailbox
     * nobody reads, with the notification row still saying {@code SENT}. Validating strictly here
     * turns that into a {@code FAILED} row with the address in the reason, at the moment it
     * happens.
     *
     * @throws NotificationDeliveryException when the address could never receive mail
     */
    private static void requireDeliverableAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new NotificationDeliveryException("No recipient address");
        }
        try {
            InternetAddress parsed = new InternetAddress(address, true);
            parsed.validate();
        } catch (AddressException ex) {
            throw new NotificationDeliveryException(
                    "Recipient is not a valid email address: " + address, ex);
        }
    }

    /**
     * The innermost message, which is the one that says what actually went wrong.
     *
     * <p>Jakarta Mail nests its causes, and the outer layer is usually a generic "failed to send"
     * that tells an operator nothing. The bottom of the chain is where "authentication failed" or
     * "connection timed out" lives, and that string ends up on the notification row.
     */
    private static String rootReason(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}

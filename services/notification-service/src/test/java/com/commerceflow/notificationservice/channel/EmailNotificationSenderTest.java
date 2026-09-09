package com.commerceflow.notificationservice.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import com.commerceflow.notificationservice.config.NotificationProperties;
import com.commerceflow.notificationservice.entity.Notification;
import com.commerceflow.notificationservice.entity.NotificationChannel;
import com.commerceflow.notificationservice.entity.NotificationStatus;
import com.commerceflow.notificationservice.entity.NotificationType;

/**
 * The SMTP adapter, without an SMTP server.
 *
 * <p>What matters here is not that Jakarta Mail works — it does — but that this class keeps the
 * two promises the saga depends on: it only claims the channel it can actually deliver, and a
 * mail server having a bad day surfaces as a {@link NotificationDeliveryException} rather than
 * something that escapes and leaves an order looking unfinished.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailNotificationSenderTest {

    @Mock
    private JavaMailSender mailSender;

    @Captor
    private ArgumentCaptor<MimeMessage> messageCaptor;

    private EmailNotificationSender sender;
    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getEmail().setFrom("no-reply@commerceflow.io");
        properties.getEmail().setFromName("CommerceFlow");

        sender = new EmailNotificationSender(mailSender, properties);

        when(mailSender.createMimeMessage())
                .thenAnswer(call -> new MimeMessage((Session) null));
    }

    @Test
    @DisplayName("claims email, and nothing else")
    void supportsOnlyEmail() {
        assertThat(sender.supports(NotificationChannel.EMAIL)).isTrue();
        // Claiming SMS here would silently swallow every text message the platform ever sends:
        // this sender is picked first, and it cannot deliver one.
        assertThat(sender.supports(NotificationChannel.SMS)).isFalse();
        assertThat(sender.supports(NotificationChannel.PUSH)).isFalse();
    }

    @Test
    @DisplayName("sends the rendered subject and body to the recipient on the notification")
    void sendsTheRenderedMessage() throws Exception {
        sender.send(notification("ada@commerceflow.io", "Your order CF-1 is confirmed"));

        verify(mailSender).send(messageCaptor.capture());
        MimeMessage sent = messageCaptor.getValue();

        assertThat(sent.getSubject()).isEqualTo("Your order CF-1 is confirmed");
        assertThat(sent.getAllRecipients()).hasSize(1);
        assertThat(sent.getAllRecipients()[0].toString()).contains("ada@commerceflow.io");
        assertThat(sent.getFrom()[0].toString()).contains("no-reply@commerceflow.io");
    }

    @Test
    @DisplayName("a reply-to is only set when one is configured")
    void replyToIsOptional() throws Exception {
        properties.getEmail().setReplyTo("  ");

        sender.send(notification("ada@commerceflow.io", "Subject"));

        verify(mailSender).send(messageCaptor.capture());
        // A blank reply-to would otherwise become a header with an empty address, which some
        // servers reject outright.
        assertThat(messageCaptor.getValue().getReplyTo()[0].toString())
                .contains("no-reply@commerceflow.io");
    }

    @Test
    @DisplayName("an SMTP failure becomes a delivery exception, not something that escapes")
    void smtpFailureIsTranslated() {
        doThrow(new MailSendException("failed to send"))
                .when(mailSender).send(any(MimeMessage.class));

        // The caller records a FAILED notification and still answers the saga. Letting this
        // propagate as a MailException would dead-letter the command and strand the step.
        assertThatThrownBy(() -> sender.send(notification("ada@commerceflow.io", "Subject")))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("SMTP delivery failed");
    }

    @Test
    @DisplayName("the recorded reason names the real cause, not the wrapper")
    void failureReasonNamesTheRootCause() {
        doThrow(new MailSendException("failed to send",
                new IllegalStateException("authentication failed")))
                .when(mailSender).send(any(MimeMessage.class));

        // This string ends up on the notification row and in the operator's face. "Failed to
        // send" tells them nothing; "authentication failed" tells them exactly what to fix.
        assertThatThrownBy(() -> sender.send(notification("ada@commerceflow.io", "Subject")))
                .hasMessageContaining("authentication failed");
    }

    @Test
    @DisplayName("a malformed recipient fails before anything is sent")
    void malformedRecipientIsRejected() {
        assertThatThrownBy(() -> sender.send(notification("not-an-address", "Subject")))
                .isInstanceOf(NotificationDeliveryException.class);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    private static Notification notification(String recipient, String subject) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .referenceId(UUID.randomUUID())
                .type(NotificationType.ORDER_CONFIRMED)
                .channel(NotificationChannel.EMAIL)
                .recipient(recipient)
                .subject(subject)
                .content("Hello,\n\nYour order is confirmed.\n\nThe CommerceFlow team")
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}

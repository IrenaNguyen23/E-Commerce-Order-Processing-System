package com.commerceflow.notificationservice.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.commerceflow.notificationservice.entity.NotificationType;

/**
 * Renders the customer-facing text of a notification.
 *
 * <p>Templates live in code rather than in the database on purpose: they are part of the product,
 * they are reviewed and versioned with it, and a missing placeholder should fail a build rather
 * than a customer email. Swapping in a template engine later only changes this one class.
 */
@Component
public class NotificationTemplateRenderer {

    // Past what Map.of takes, so Map.ofEntries it is. The comment that used to live here
    // predicted exactly this and was right.
    private static final Map<NotificationType, Template> TEMPLATES = Map.ofEntries(
            Map.entry(NotificationType.USER_WELCOME, new Template(
                    "Welcome to CommerceFlow",
                    """
                    Hello {fullName},

                    Your CommerceFlow account is ready. You can now browse the catalogue and \
                    place your first order.

                    The CommerceFlow team""")),

            Map.entry(NotificationType.ORDER_CONFIRMED, new Template(
                    "Your order {orderNumber} is confirmed",
                    """
                    Hello,

                    Good news: order {orderNumber} has been paid and confirmed.

                    Total: {totalAmount} {currency}
                    Payment reference: {paymentId}

                    We will let you know as soon as it ships.

                    The CommerceFlow team""")),

            // {refundLine} rather than a fixed sentence. This template used to end with
            // "You have not been charged", which is true of an order cancelled before payment
            // and is the worst possible thing to tell a customer who is owed a refund. The
            // sender knows which case it is; the template does not, so it asks.
            Map.entry(NotificationType.ORDER_CANCELLED, new Template(
                    "Your order {orderNumber} could not be completed",
                    """
                    Hello,

                    Unfortunately order {orderNumber} could not be completed and has been \
                    cancelled at the {failedStep} step.

                    Reason: {reason}

                    {refundLine}

                    Anything that was reserved for this order has been released.

                    The CommerceFlow team""")),

            Map.entry(NotificationType.EMAIL_VERIFICATION, new Template(
                    "Confirm your email address",
                    """
                    Hello {fullName},

                    Welcome to CommerceFlow. One step left: confirm this address so we can                     send you order updates.

                    {verificationUrl}

                    If you did not create an account, you can ignore this message — nothing                     happens until the link is used.

                    The CommerceFlow team""")),

            Map.entry(NotificationType.PASSWORD_RESET, new Template(
                    "Reset your CommerceFlow password",
                    """
                    Hello {fullName},

                    Use this link to set a new password. It works once and expires in                     {expiresInMinutes} minutes.

                    {resetUrl}

                    If you did not ask for this, you can ignore it. Your password has not                     changed and nobody can change it without this link.

                    The CommerceFlow team""")),

            Map.entry(NotificationType.PASSWORD_CHANGED, new Template(
                    "Your CommerceFlow password was changed",
                    """
                    Hello {fullName},

                    Your password has just been changed, and every device that was signed in                     has been signed out.

                    If this was you, there is nothing to do.

                    If it was not, someone else has access to your email. Reset your password                     again immediately and contact us.

                    The CommerceFlow team""")),

            // ------------------------------------------------------------------ returns
            //
            // Three messages, not six. Approved, refused and refunded are the points where the
            // customer either has to do something or has been waiting for an answer. "We
            // received your parcel" is not one: it lands a day before the refund and teaches
            // people that mail from this shop can be ignored.
            Map.entry(NotificationType.RETURN_APPROVED, new Template(
                    "Your return for order {orderNumber} is approved",
                    """
                    Hello,

                    We have approved your return for order {orderNumber}.

                    {note}

                    Please send the items back to us. Pack them as they arrived where you can —                     we can only refund goods that reach us in a condition we could sell.

                    Once they are here we will refund {refundAmount}. Card refunds take a few                     days to appear on a statement after that.

                    The CommerceFlow team""")),

            // The message reporting a decision the customer will not like, so it has to say why
            // and what they can do. A refusal with nothing after it produces a support call.
            Map.entry(NotificationType.RETURN_REJECTED, new Template(
                    "About your return for order {orderNumber}",
                    """
                    Hello,

                    We are not able to accept the return you asked for on order {orderNumber}.

                    {note}

                    If you think this is wrong, reply to this message and a person will look at                     it again. Nothing about this affects your rights if the goods turn out to be                     faulty.

                    The CommerceFlow team""")),

            Map.entry(NotificationType.RETURN_REFUNDED, new Template(
                    "Your refund for order {orderNumber}",
                    """
                    Hello,

                    We have refunded {refundAmount} for order {orderNumber}, to the payment                     method you used.

                    Card refunds usually take a few working days to appear on a statement. If it                     has not arrived within a week, reply to this message and we will chase it.

                    The CommerceFlow team""")),

            Map.entry(NotificationType.SHIPMENT_DISPATCHED, new Template(
                    "Your order {orderNumber} is on its way",
                    """
                    Hello,

                    Order {orderNumber} has left us{carrierLine}.

                    {trackingLine}

                    The CommerceFlow team""")),

            // The one message here that reports a problem, so it is the one that has to say
            // what happens next. A notification that leaves somebody with nothing to do
            // generates a support call instead.
            Map.entry(NotificationType.SHIPMENT_ATTEMPTED, new Template(
                    "We could not deliver order {orderNumber}",
                    """
                    Hello,

                    The carrier tried to deliver order {orderNumber} and could not.

                    {trackingLine}

                    They will normally try again on the next working day. If you would rather \
                    collect it or change the address, reply to this message and we will arrange it.

                    The CommerceFlow team""")),

            Map.entry(NotificationType.SHIPMENT_DELIVERED, new Template(
                    "Your order {orderNumber} has arrived",
                    """
                    Hello,

                    Order {orderNumber} has been delivered.

                    If it has not reached you, reply to this message — a parcel marked as \
                    delivered has occasionally been left with a neighbour or at the wrong door, \
                    and we would rather hear about it than not.

                    The CommerceFlow team""")),

            Map.entry(NotificationType.SHIPMENT_RETURNED, new Template(
                    "Order {orderNumber} has come back to us",
                    """
                    Hello,

                    Order {orderNumber} has been returned to us.

                    We will be in touch shortly about a refund or a redelivery — you do not \
                    need to do anything in the meantime.

                    The CommerceFlow team""")),

            Map.entry(NotificationType.GENERIC, new Template(
                    "{subject}",
                    """
                    Hello,

                    {body}

                    The CommerceFlow team""")));

    /** @return the rendered subject line, with every known placeholder substituted */
    public String renderSubject(NotificationType type, Map<String, String> params) {
        return substitute(template(type).subject(), params);
    }

    /** @return the rendered message body */
    public String renderBody(NotificationType type, Map<String, String> params) {
        return substitute(template(type).body(), params);
    }

    private static Template template(NotificationType type) {
        return TEMPLATES.getOrDefault(type, TEMPLATES.get(NotificationType.GENERIC));
    }

    /**
     * Replaces {@code {placeholder}} tokens.
     *
     * <p>An unmatched placeholder is replaced with an empty string rather than left in the text:
     * a customer should never receive a message containing literal braces.
     */
    private static String substitute(String template, Map<String, String> params) {
        Map<String, String> safe = params == null ? Map.of() : new HashMap<>(params);
        StringBuilder rendered = new StringBuilder(template.length());

        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf('{', cursor);
            if (open < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }
            rendered.append(template, cursor, open);
            String key = template.substring(open + 1, close);
            rendered.append(safe.getOrDefault(key, ""));
            cursor = close + 1;
        }
        return rendered.toString().trim();
    }

    private record Template(String subject, String body) {
    }
}

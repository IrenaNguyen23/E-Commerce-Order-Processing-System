import { Link } from 'react-router-dom';

import { Clause, Fill, LegalDocument } from '@/features/legal/components/legal-document';
import { paths } from '@/routes/paths';

/**
 * Returns and refunds.
 *
 * <p>The one legal page with a working feature behind it: the return request flow at
 * {@link paths.returns} is real, so what this page promises and what the software does have to
 * agree. If the wording here changes — a different window, a different postage rule — the flow
 * has to change with it.
 */
export default function ReturnsPage() {
  return (
    <LegalDocument
      title="Returns and refunds"
      summary="Changing your mind, and what to do when something arrives faulty."
      lastUpdated="2026-08-28"
    >
      <Clause heading="Changing your mind">
        <p>
          You can return most things within <Fill>[14 days, or your longer window]</Fill> of
          delivery for any reason at all. You do not have to explain why.
        </p>
        <p>
          For consumers in the EU this is a statutory right and fourteen days is the legal minimum,
          not a choice. A shop may offer longer; it may not offer less.
        </p>
      </Clause>

      <Clause heading="How to return something">
        <p>
          Open the order under{' '}
          <Link to={paths.orders} className="underline">my orders</Link> and choose{' '}
          <strong>Return items</strong>. Pick what is going back and say why. We will confirm by
          email with the address to send it to.
        </p>
        <p>
          Send it back within <Fill>[return shipping window]</Fill> of telling us. Items should be
          in a condition you would accept yourself — you may unpack and inspect something the way
          you would in a shop, but not use it.
        </p>
      </Clause>

      <Clause heading="Who pays the postage">
        <p>
          <Fill>[Say plainly: we pay, or you pay, or we pay for faulty items only.]</Fill>
        </p>
        <p>
          This has to be stated before purchase. Where it is not, EU law makes the seller pay —
          so leaving this blank is a decision, and an expensive one.
        </p>
      </Clause>

      <Clause heading="When you get your money back">
        <p>
          We refund within <Fill>[refund window]</Fill> of receiving the item back, to the payment
          method you used. Card refunds then take a few more days to appear on a statement, which
          is the bank and not us.
        </p>
        <p>
          The refund covers what you paid for the items, and the original standard delivery charge
          when you return an entire order. If you chose a faster delivery than our standard one, we
          refund the standard cost rather than the premium.
        </p>
        <p>
          A partial return refunds the items sent back. Where a discount code applied to the order,
          the refund is the amount actually paid for those items after the discount — not their
          full price.
        </p>
      </Clause>

      <Clause heading="Faulty or wrong items">
        <p>
          If something arrives damaged, faulty, or is simply not what you ordered, tell us and we
          will put it right at our cost — repair, replacement or refund. This is separate from
          changing your mind and is not limited to the window above.
        </p>
        <p>Tell us within <Fill>[damage reporting window]</Fill> of delivery.</p>
      </Clause>

      <Clause heading="What cannot be returned">
        <p>
          Some things are excluded by law once opened or made to order. List yours here:{' '}
          <Fill>[exclusions, e.g. personalised items, sealed goods opened after delivery]</Fill>.
        </p>
        <p>
          An exclusion has to be lawful and stated before purchase. &quot;No returns&quot; on
          ordinary stock is not enforceable against a consumer.
        </p>
      </Clause>

      <Clause heading="Cancelling before it ships">
        <p>
          An order that has not been dispatched can be cancelled outright from the order page, and
          you are refunded in full. That is quicker for everybody than returning a parcel that has
          not left the building.
        </p>
      </Clause>
    </LegalDocument>
  );
}

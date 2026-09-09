import { Link } from 'react-router-dom';

import { Clause, Fill, LegalDocument } from '@/features/legal/components/legal-document';
import { paths } from '@/routes/paths';

/**
 * Terms of sale.
 *
 * Placeholder wording — see `LegalDocument` for why the draft banner exists and how to remove it.
 */
export default function TermsPage() {
  return (
    <LegalDocument
      title="Terms of sale"
      summary="The agreement between you and the shop when you place an order."
      lastUpdated="2026-08-28"
    >
      <Clause heading="Who you are buying from">
        <p>
          These goods are sold by <Fill>[legal company name]</Fill>, registered at{' '}
          <Fill>[registered address]</Fill>, company number <Fill>[company number]</Fill>, VAT
          number <Fill>[VAT number]</Fill>. You can reach us at <Fill>[support email]</Fill> and{' '}
          <Fill>[telephone number]</Fill>.
        </p>
        <p>
          Every one of those is legally required on a shop selling to consumers, and every one has
          to be true. See <Link to={paths.contact} className="underline">Contact</Link>.
        </p>
      </Clause>

      <Clause heading="When a contract is formed">
        <p>
          Placing an order is an offer to buy. A contract is formed when we confirm the order by
          email — not when the basket is submitted and not when payment is authorised.
        </p>
        <p>
          This distinction matters: it is what lets us refuse an order priced wrongly, or one for
          stock that sold out between the basket and the checkout, without being in breach.
        </p>
      </Clause>

      <Clause heading="Prices and tax">
        <p>
          Prices shown include tax at the rate for the delivery country. That rate is decided by
          where the order is going, so the total can change when you change the delivery address —
          the checkout shows the breakdown before you pay.
        </p>
        <p>
          Delivery is charged separately and shown before payment. The price you pay is the one on
          the confirmation; a later price change does not affect an order already placed.
        </p>
      </Clause>

      <Clause heading="Delivery">
        <p>
          We aim to dispatch within <Fill>[dispatch window]</Fill> and deliver within{' '}
          <Fill>[delivery window]</Fill>. Those are estimates. Where a delivery date is promised at
          checkout, it is shown on the order and in the tracking.
        </p>
        <p>
          Risk passes to you on delivery. If a parcel arrives damaged, tell us within{' '}
          <Fill>[damage reporting window]</Fill>.
        </p>
      </Clause>

      <Clause heading="Cancelling and returning">
        <p>
          You can cancel an order yourself while it is still being processed. Once it has shipped,
          the <Link to={paths.returns} className="underline">returns policy</Link> applies — it
          covers both the statutory right to change your mind and what to do with a faulty item.
        </p>
      </Clause>

      <Clause heading="If something is wrong with what you bought">
        <p>
          Goods must match their description and be of satisfactory quality. Nothing in these terms
          limits the rights you have by law, and where these terms and the law disagree, the law
          wins.
        </p>
      </Clause>

      <Clause heading="Your account">
        <p>
          Keep your password to yourself. Tell us at once if you think somebody else has been using
          your account. We may suspend an account being used fraudulently.
        </p>
        <p>
          You can close your account at any time from your profile. What happens to your data then
          is described in the{' '}
          <Link to={paths.privacy} className="underline">privacy notice</Link>.
        </p>
      </Clause>

      <Clause heading="Complaints, and the law that applies">
        <p>
          Write to <Fill>[complaints email]</Fill> and we will reply within{' '}
          <Fill>[response time]</Fill>. These terms are governed by the law of{' '}
          <Fill>[jurisdiction]</Fill>.
        </p>
        <p>
          Consumers can also use the EU online dispute resolution platform. If the shop sells into
          the EU, that link is required here: <Fill>[ODR platform link]</Fill>.
        </p>
      </Clause>
    </LegalDocument>
  );
}

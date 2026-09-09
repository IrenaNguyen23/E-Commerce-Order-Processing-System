import { Link } from 'react-router-dom';

import { Clause, Fill, LegalDocument } from '@/features/legal/components/legal-document';
import { paths } from '@/routes/paths';

/**
 * Who the shop is, and how to reach a person.
 *
 * <h2>Why this is a legal page and not a marketing one</h2>
 *
 * A shop selling to consumers has to publish who is behind it — legal name, address, registration
 * and a way to contact them that is not a form. That is what this page is for. A contact form on
 * top of it is fine; a contact form instead of it is not.
 *
 * <p>There is deliberately no form here yet: a form that emails nobody is worse than an address,
 * because the customer believes they have been in touch. When there is a mailbox somebody reads,
 * add one.
 */
export default function ContactPage() {
  return (
    <LegalDocument
      title="Contact"
      summary="Who runs this shop, and how to reach us."
      lastUpdated="2026-08-28"
    >
      <Clause heading="Getting in touch">
        <p>
          Email <Fill>[support email]</Fill> — we reply within <Fill>[response time]</Fill> on
          working days.
        </p>
        <p>
          Telephone <Fill>[telephone number]</Fill>, <Fill>[opening hours]</Fill>.
        </p>
        <p>
          If you are writing about an order, quote the order number. It is on your confirmation
          email and on the order under{' '}
          <Link to={paths.orders} className="underline">my orders</Link>, and having it turns a
          conversation into an answer.
        </p>
      </Clause>

      <Clause heading="Company details">
        <ul>
          <li><Fill>[legal company name]</Fill></li>
          <li><Fill>[registered address]</Fill></li>
          <li>Company number <Fill>[company number]</Fill></li>
          <li>VAT number <Fill>[VAT number]</Fill></li>
          <li>Registered with <Fill>[registry / chamber of commerce]</Fill></li>
        </ul>
        <p>
          These are required on the site of any business selling to consumers, and required to be
          accurate. A trading name on its own is not enough.
        </p>
      </Clause>

      <Clause heading="Returns">
        <p>
          Do not send anything back before telling us — start it from the order page so we can give
          you the right address and match the parcel to your refund. The{' '}
          <Link to={paths.returns} className="underline">returns policy</Link> has the detail.
        </p>
      </Clause>

      <Clause heading="Privacy questions">
        <p>
          Anything about your personal data goes to <Fill>[privacy contact email]</Fill>. What we
          hold and how to have it removed is in the{' '}
          <Link to={paths.privacy} className="underline">privacy notice</Link>.
        </p>
      </Clause>

      <Clause heading="Complaints">
        <p>
          Write to <Fill>[complaints email]</Fill>. If we cannot settle it between us, consumers
          in the EU can use the online dispute resolution platform:{' '}
          <Fill>[ODR platform link]</Fill>.
        </p>
      </Clause>
    </LegalDocument>
  );
}

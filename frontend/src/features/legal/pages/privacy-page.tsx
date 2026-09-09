import { Link } from 'react-router-dom';

import { Clause, Fill, LegalDocument } from '@/features/legal/components/legal-document';
import { paths } from '@/routes/paths';

/**
 * Privacy notice.
 *
 * Placeholder wording, but the factual parts are not invented: what the platform actually stores,
 * how long it keeps it and what erasure does are taken from `docs/data-retention.md` and the
 * erasure implementation. Those paragraphs should survive a rewrite; a lawyer will change the
 * framing, not the facts.
 */
export default function PrivacyPage() {
  return (
    <LegalDocument
      title="Privacy notice"
      summary="What we hold about you, why, for how long, and how to have it removed."
      lastUpdated="2026-08-28"
    >
      <Clause heading="Who is responsible">
        <p>
          <Fill>[legal company name]</Fill> of <Fill>[registered address]</Fill> decides how your
          personal data is used. Questions go to <Fill>[privacy contact email]</Fill>.
        </p>
        <p>
          If the shop needs a data protection officer, their details belong here:{' '}
          <Fill>[DPO details, or "not required"]</Fill>.
        </p>
      </Clause>

      <Clause heading="What we hold, and why">
        <ul>
          <li>
            <strong>Your account</strong> — name, email address, phone number if you give one. To
            let you sign in and to reach you about an order. Needed to perform the contract.
          </li>
          <li>
            <strong>Your addresses</strong> — to deliver, and to work out the tax rate.
          </li>
          <li>
            <strong>Your orders</strong> — what you bought, what you paid, where it went. Needed to
            perform the contract, and kept afterwards because tax law requires records of sales.
          </li>
          <li>
            <strong>Payments</strong> — handled by <Fill>[payment provider]</Fill>. We never see or
            store your card number; we keep the provider&apos;s reference so we can match a
            refund to a payment.
          </li>
          <li>
            <strong>Reviews you write</strong> — published with the name on your account.
          </li>
          <li>
            <strong>Sign-in activity</strong> — failed attempts, briefly, so an account can be
            locked after repeated wrong passwords.
          </li>
        </ul>
      </Clause>

      <Clause heading="How long we keep it">
        <p>
          Orders and payments are kept as long as tax law requires — <Fill>[retention period]</Fill>{' '}
          in <Fill>[jurisdiction]</Fill>. Records of administrative actions taken on your account
          are kept for two years. An abandoned basket is deleted after four months, and sign-in
          sessions expire within days.
        </p>
      </Clause>

      <Clause heading="Deleting your account">
        <p>
          You can delete your account from your profile at any time. Be clear about what that does,
          because it is not the same as deleting everything:
        </p>
        <ul>
          <li>
            Your name, email address, phone number and saved addresses are removed. The account can
            never be signed into again.
          </li>
          <li>
            Your basket and wishlist are deleted outright.
          </li>
          <li>
            Your orders are <strong>kept, with you removed from them</strong>. An order is a
            financial record and we are required to keep it; what stays is the amount, the tax and
            the country, none of which identifies you.
          </li>
          <li>
            Reviews you wrote stay published, with the author name replaced. Other customers are
            reading them and product ratings depend on them.
          </li>
        </ul>
        <p>
          <strong>This cannot be undone.</strong> Anonymisation destroys what would be needed to
          reverse it, which is the point of it.
        </p>
      </Clause>

      <Clause heading="Backups">
        <p>
          Deleting something removes it from the live system immediately. Backups taken beforehand
          still contain it until they expire, currently after <Fill>[backup retention]</Fill>. This
          is normal and permitted — a backup is not a system we use to make decisions about you —
          but it is stated rather than left to be discovered.
        </p>
      </Clause>

      <Clause heading="Who else sees your data">
        <ul>
          <li><Fill>[payment provider]</Fill> — to take payment and issue refunds.</li>
          <li><Fill>[delivery carriers]</Fill> — the delivery address and your name.</li>
          <li><Fill>[email provider]</Fill> — to send order and account emails.</li>
          <li><Fill>[hosting provider and region]</Fill> — where the system runs.</li>
        </ul>
        <p>
          We do not sell your data. If any of the above is outside your country, the safeguards for
          that transfer belong here: <Fill>[transfer safeguards]</Fill>.
        </p>
      </Clause>

      <Clause heading="Your rights">
        <p>
          You can ask for a copy of what we hold, ask us to correct it, ask us to delete it, or
          object to how we use it. Write to <Fill>[privacy contact email]</Fill> and we will answer
          within one month.
        </p>
        <p>
          You can also complain to your data protection authority:{' '}
          <Fill>[supervisory authority]</Fill>.
        </p>
      </Clause>

      <Clause heading="Cookies">
        <p>
          The shop stores your sign-in session and your basket. Both are needed for it to work, so
          neither asks for consent. If analytics or advertising are ever added, that changes and
          this section — plus a consent banner — has to be written properly:{' '}
          <Fill>[analytics in use, or "none"]</Fill>.
        </p>
        <p>
          See also the <Link to={paths.terms} className="underline">terms of sale</Link>.
        </p>
      </Clause>
    </LegalDocument>
  );
}

import { AlertTriangle } from 'lucide-react';
import type { ReactNode } from 'react';

/**
 * The shell every legal page uses.
 *
 * <h2>The draft banner is not decoration</h2>
 *
 * Everything under `src/features/legal` is **placeholder wording written by a developer**. It has
 * the right shape — the headings a lawyer will expect, the fields that must be filled in — and
 * none of the authority. Published as-is it would be a shop making binding statements nobody
 * qualified has read.
 *
 * So the banner stays visible until somebody replaces the content, and removing it is a deliberate
 * act: set `draft={false}` on a page once its wording has actually been reviewed. A quiet
 * placeholder that looks finished is worse than no page at all, because nobody goes looking for it.
 *
 * <h2>Why these are hand-written pages and not a CMS</h2>
 *
 * There are four of them and they change perhaps twice a year. A CMS for that is a database, an
 * editor, a permissions model and a migration — all to avoid editing four files. When the shop
 * needs pages that marketing changes weekly, that is the moment for a CMS, not before.
 */

interface LegalDocumentProps {
  title: string;
  /** One line saying what this page is for, in the reader's terms. */
  summary: string;
  /** Shown to the reader, and it matters: consumer law asks which version they agreed to. */
  lastUpdated: string;
  /** Whether the wording is still the developer placeholder. */
  draft?: boolean;
  children: ReactNode;
}

export function LegalDocument({
  title,
  summary,
  lastUpdated,
  draft = true,
  children,
}: LegalDocumentProps) {
  return (
    <div className="container max-w-3xl py-10">
      <header className="mb-8">
        <h1 className="text-3xl font-semibold tracking-tight">{title}</h1>
        <p className="mt-2 text-muted-foreground">{summary}</p>
        <p className="mt-4 text-sm text-muted-foreground">
          Last updated <time dateTime={lastUpdated}>{lastUpdated}</time>
        </p>
      </header>

      {draft ? (
        <div
          role="note"
          className="mb-8 flex items-start gap-3 rounded-lg border border-warning bg-warning/10 p-4 text-sm"
        >
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden />
          <div>
            <p className="font-medium">Placeholder wording — not legal advice.</p>
            <p className="text-muted-foreground">
              This page has the structure a document like this needs and none of the authority.
              Replace the text with wording your own legal advisor has approved, fill in every{' '}
              <Fill>bracketed field</Fill>, then set <code>draft={'{false}'}</code> on this page to
              remove this notice.
            </p>
          </div>
        </div>
      ) : null}

      {/*
        Long-form prose, so the measure is capped and the rhythm is set here once rather than by
        every page repeating spacing classes.
      */}
      <div className="space-y-8 text-sm leading-relaxed [&_h2]:text-lg [&_h2]:font-semibold [&_h2]:tracking-tight [&_p]:mt-3 [&_ul]:mt-3 [&_ul]:list-disc [&_ul]:space-y-1 [&_ul]:pl-5">
        {children}
      </div>
    </div>
  );
}

/** A section of the document. */
export function Clause({ heading, children }: { heading: string; children: ReactNode }) {
  return (
    <section>
      <h2>{heading}</h2>
      {children}
    </section>
  );
}

/**
 * Something the shop's owner has to supply — a company number, an address, a deadline.
 *
 * <p>Marked rather than left as ordinary text, so an unfilled field is impossible to miss on the
 * page and searchable in the source. A placeholder that reads like real content is how a shop ends
 * up publishing "[COMPANY NAME]" to customers.
 */
export function Fill({ children }: { children: ReactNode }) {
  return (
    <mark className="rounded bg-warning/25 px-1 py-0.5 font-medium text-foreground">
      {children}
    </mark>
  );
}

import { AlertTriangle, ScrollText, Search, X } from 'lucide-react';
import { useMemo, useState } from 'react';

import { PageHeader } from '@/components/common/page-header';
import { EmptyState, ErrorState } from '@/components/common/states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import {
  AUDIT_SERVICES,
  useAuditActions,
  useAuditTrail,
  type AuditEntry,
  type AuditFilters,
  type AuditServiceKey,
} from '@/features/admin/audit-api';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { cn } from '@/utils/cn';
import { formatDateTime, humanize } from '@/utils/format';

/**
 * What operators did, and when.
 *
 * <h2>Five logs shown as one</h2>
 *
 * Each service keeps its own audit table, written in the same transaction as the change it
 * describes. This screen asks all five and merges the answers, newest first.
 *
 * <h2>An incomplete trail is never shown as a complete one</h2>
 *
 * That is the whole reason this screen is more than a table. If one service cannot be reached, its
 * rows are missing — and missing rows look exactly like a service where nothing happened, which is
 * the opposite conclusion. So a failure is named at the top of the list rather than swallowed, and
 * the reader is told what they are not looking at.
 *
 * <h2>Read only, and it will stay that way</h2>
 *
 * Nothing on this screen edits or deletes an entry, and the database refuses both anyway. A trail
 * that can be amended through the console it is read in answers a different question from the one
 * anybody asks it.
 */

const EMPTY_FILTERS: AuditFilters = {
  services: [],
  actor: '',
  action: '',
  targetType: '',
  targetId: '',
  correlationId: '',
  from: '',
  to: '',
};

export default function AdminAuditPage() {
  /** What is typed. Applied on submit, so a half-typed email does not fire five requests. */
  const [draft, setDraft] = useState<AuditFilters>(EMPTY_FILTERS);
  const [applied, setApplied] = useState<AuditFilters>(EMPTY_FILTERS);

  const trail = useAuditTrail(applied);
  const actions = useAuditActions();

  const entries = useMemo(
    () => trail.data?.pages.flatMap((page) => page.entries) ?? [],
    [trail.data],
  );

  /**
   * Services that failed on any page fetched so far.
   *
   * <p>Across every page, not only the latest: a service that dropped out halfway through means
   * everything below that point is incomplete, and scrolling further must not clear the warning.
   */
  const unavailable = useMemo(() => {
    const names = new Set<AuditServiceKey>();
    trail.data?.pages.forEach((page) => page.unavailable.forEach((key) => names.add(key)));
    return [...names];
  }, [trail.data]);

  const hasFilters = Object.entries(applied).some(([key, value]) =>
    key === 'services' ? (value as AuditServiceKey[]).length > 0 : Boolean(value),
  );

  function toggleService(key: AuditServiceKey) {
    setDraft((current) => ({
      ...current,
      services: current.services.includes(key)
        ? current.services.filter((service) => service !== key)
        : [...current.services, key],
    }));
  }

  function apply(event: React.FormEvent) {
    event.preventDefault();
    setApplied(draft);
  }

  function clear() {
    setDraft(EMPTY_FILTERS);
    setApplied(EMPTY_FILTERS);
  }

  return (
    <div>
      <PageHeader
        title="Audit trail"
        description="Every administrative action, newest first. Read only."
        breadcrumbs={[{ label: 'Admin', to: paths.admin.dashboard }, { label: 'Audit trail' }]}
      />

      {/* ---------------------------------------------------------------- filters */}
      <Card className="mb-6">
        <CardContent className="pt-6">
          <form onSubmit={apply} className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <Field
                id="audit-actor"
                label="Who"
                placeholder="Part of an email address"
                value={draft.actor ?? ''}
                onChange={(actor) => setDraft((current) => ({ ...current, actor }))}
              />

              <div className="space-y-2">
                <Label htmlFor="audit-action">Action</Label>
                <Input
                  id="audit-action"
                  list="audit-actions"
                  placeholder="Any"
                  value={draft.action ?? ''}
                  onChange={(event) =>
                    setDraft((current) => ({ ...current, action: event.target.value }))
                  }
                />
                {/*
                  A datalist rather than a select: the options come from what the services have
                  actually recorded, and a free-text field still works if one of them could not be
                  asked.
                */}
                <datalist id="audit-actions">
                  {(actions.data ?? []).map((action) => (
                    <option key={action} value={action} />
                  ))}
                </datalist>
              </div>

              <Field
                id="audit-target"
                label="Thing it happened to"
                placeholder="Order, product or account id"
                value={draft.targetId ?? ''}
                onChange={(targetId) => setDraft((current) => ({ ...current, targetId }))}
              />

              <Field
                id="audit-correlation"
                label="Correlation id"
                placeholder="One customer action, across services"
                value={draft.correlationId ?? ''}
                onChange={(correlationId) =>
                  setDraft((current) => ({ ...current, correlationId }))
                }
              />

              <Field
                id="audit-from"
                label="From"
                type="datetime-local"
                value={draft.from ?? ''}
                onChange={(from) => setDraft((current) => ({ ...current, from: toIso(from) }))}
                raw={fromIso(draft.from)}
              />

              <Field
                id="audit-to"
                label="To"
                type="datetime-local"
                value={draft.to ?? ''}
                onChange={(to) => setDraft((current) => ({ ...current, to: toIso(to) }))}
                raw={fromIso(draft.to)}
              />
            </div>

            <div className="space-y-2">
              <Label>Where to look</Label>
              <div className="flex flex-wrap gap-2">
                {AUDIT_SERVICES.map((service) => {
                  const selected = draft.services.includes(service.key);
                  return (
                    <button
                      key={service.key}
                      type="button"
                      onClick={() => toggleService(service.key)}
                      aria-pressed={selected}
                      className={cn(
                        'rounded-full border px-3 py-1 text-xs font-medium transition-colors',
                        selected
                          ? 'border-transparent bg-primary text-primary-foreground'
                          : 'text-muted-foreground hover:bg-muted',
                      )}
                    >
                      {service.label}
                    </button>
                  );
                })}
              </div>
              <p className="text-xs text-muted-foreground">
                None selected means all five. Narrowing is faster; it is not more correct.
              </p>
            </div>

            <div className="flex gap-2">
              <Button type="submit">
                <Search className="mr-2 h-4 w-4" />
                Search
              </Button>
              {hasFilters ? (
                <Button type="button" variant="ghost" onClick={clear}>
                  <X className="mr-2 h-4 w-4" />
                  Clear
                </Button>
              ) : null}
            </div>
          </form>
        </CardContent>
      </Card>

      {/* ---------------------------------------------------------------- the trail */}
      {unavailable.length > 0 ? (
        <div
          role="status"
          className="mb-4 flex items-start gap-3 rounded-lg border border-warning bg-warning/10 p-4 text-sm"
        >
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
          <div>
            <p className="font-medium">This trail is incomplete.</p>
            <p className="text-muted-foreground">
              {labelsFor(unavailable)} did not answer, so nothing recorded there is shown. An
              empty result below does not mean nothing happened.
            </p>
          </div>
        </div>
      ) : null}

      {trail.isError ? (
        <ErrorState error={normalizeError(trail.error)} onRetry={() => void trail.refetch()} />
      ) : trail.isLoading ? (
        <div className="space-y-3">
          {[0, 1, 2, 3, 4].map((row) => (
            <Skeleton key={row} className="h-20 w-full rounded-lg" />
          ))}
        </div>
      ) : entries.length === 0 ? (
        <EmptyState
          icon={<ScrollText className="h-10 w-10" />}
          title={hasFilters ? 'Nothing matches' : 'Nothing recorded yet'}
          description={
            hasFilters
              ? 'No administrative action matches those filters. Widen the dates, or clear them.'
              : 'Administrative actions appear here as they happen — role changes, stock corrections, cancellations, refunds, moderation.'
          }
        />
      ) : (
        <>
          <ul className="space-y-2">
            {entries.map((entry) => (
              <li key={`${entry.service}:${entry.id}`}>
                <AuditRow entry={entry} />
              </li>
            ))}
          </ul>

          <div className="mt-6 flex items-center justify-center gap-4">
            {trail.hasNextPage ? (
              <Button
                variant="outline"
                onClick={() => void trail.fetchNextPage()}
                disabled={trail.isFetchingNextPage}
              >
                {trail.isFetchingNextPage ? 'Loading…' : 'Load older'}
              </Button>
            ) : (
              <p className="text-sm text-muted-foreground">
                That is the whole trail, as far back as the retention keeps it.
              </p>
            )}
          </div>
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// One entry
// ---------------------------------------------------------------------------------------------

function AuditRow({ entry }: { entry: AuditEntry }) {
  return (
    <Card>
      <CardContent className="flex flex-col gap-2 py-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="outline">{humanize(entry.action)}</Badge>
            <span className="text-xs text-muted-foreground">{entry.service}</span>
          </div>

          <p className="text-sm">{entry.summary ?? humanize(entry.action)}</p>

          <p className="text-xs text-muted-foreground">
            {/*
              No actor is an answer, not a gap: an event arrived, or a scheduled job ran. Saying
              "System" is clearer than an empty column, which reads as data that failed to load.
            */}
            {entry.actorEmail ?? 'System'}
            {entry.targetId ? (
              <>
                {' · '}
                {entry.targetType.toLowerCase()} <code className="text-xs">{entry.targetId}</code>
              </>
            ) : null}
            {entry.sourceIp ? <> {' · '}from {entry.sourceIp}</> : null}
          </p>

          {entry.correlationId ? (
            <p className="text-xs text-muted-foreground">
              correlation <code>{entry.correlationId}</code>
            </p>
          ) : null}
        </div>

        <time
          dateTime={entry.occurredAt}
          className="shrink-0 text-xs tabular-nums text-muted-foreground"
        >
          {formatDateTime(entry.occurredAt)}
        </time>
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------------------------------------
// Bits
// ---------------------------------------------------------------------------------------------

interface FieldProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  type?: string;
  /** Display value, when it differs from what is stored — the date inputs, which store ISO. */
  raw?: string;
}

function Field({ id, label, value, onChange, placeholder, type = 'text', raw }: FieldProps) {
  return (
    <div className="space-y-2">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type={type}
        placeholder={placeholder}
        value={raw ?? value}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}

function labelsFor(keys: AuditServiceKey[]): string {
  const labels = keys.map(
    (key) => AUDIT_SERVICES.find((service) => service.key === key)?.label ?? key,
  );

  const last = labels.pop();
  if (!last) return '';
  return labels.length === 0 ? last : `${labels.join(', ')} and ${last}`;
}

/**
 * `datetime-local` gives back local wall-clock time with no zone; the API wants an instant.
 *
 * Converting through Date applies the browser's offset, which is what somebody typing "09:00"
 * means — they mean nine o'clock where they are, not nine UTC.
 */
function toIso(local: string): string {
  if (!local) return '';
  const parsed = new Date(local);
  return Number.isNaN(parsed.getTime()) ? '' : parsed.toISOString();
}

/** And back again, so the field shows what was chosen rather than a UTC string. */
function fromIso(iso: string | undefined): string | undefined {
  if (!iso) return undefined;
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return undefined;

  const offsetMinutes = parsed.getTimezoneOffset();
  return new Date(parsed.getTime() - offsetMinutes * 60_000).toISOString().slice(0, 16);
}

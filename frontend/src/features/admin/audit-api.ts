import { useInfiniteQuery, useQuery } from '@tanstack/react-query';

import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';

/**
 * The operator audit trail.
 *
 * <h2>Five logs, one screen</h2>
 *
 * The audit log is deliberately not centralised. Each service writes its own rows into its own
 * database, in the same transaction as the change they describe — a shared audit table would be a
 * synchronous dependency on the write path of every service, so an audit database being down would
 * stop the shop accepting changes.
 *
 * The cost lands here: a merged view is five requests, combined in the browser. Everything below
 * exists to do that merge without ever showing an incomplete trail as if it were complete.
 */

export const AUDIT_SERVICES = [
  { key: 'auth', label: 'Accounts' },
  { key: 'orders', label: 'Orders' },
  { key: 'inventory', label: 'Catalogue' },
  { key: 'payments', label: 'Payments' },
  { key: 'notifications', label: 'Notifications' },
] as const;

export type AuditServiceKey = (typeof AUDIT_SERVICES)[number]['key'];

export interface AuditEntry {
  id: string;
  /** Which service recorded it. The back end stamps this; a merged list needs it to say where. */
  service: string;
  /** Null for something the system did on its own — an event arrived, or a job ran. */
  actorId: string | null;
  actorEmail: string | null;
  action: string;
  targetType: string;
  targetId: string | null;
  summary: string | null;
  sourceIp: string | null;
  correlationId: string | null;
  occurredAt: string;
}

/** One service's answer. */
interface AuditSlice {
  entries: AuditEntry[];
  hasMore: boolean;
  nextBeforeAt: string | null;
  nextBeforeId: string | null;
}

export interface AuditFilters {
  services: AuditServiceKey[];
  actor?: string;
  action?: string;
  targetType?: string;
  targetId?: string;
  correlationId?: string;
  from?: string;
  to?: string;
}

/** A position in the merged stream. Not a page number — see the merge below. */
interface AuditCursor {
  beforeAt: string;
  beforeId: string;
}

export interface AuditPage {
  entries: AuditEntry[];
  cursor: AuditCursor | null;
  /**
   * Services that did not answer this request.
   *
   * Never silently dropped. A trail with a service missing looks exactly like a trail where
   * nothing happened in that service, and those are opposite conclusions.
   */
  unavailable: AuditServiceKey[];
}

const PAGE_SIZE = 50;

// ---------------------------------------------------------------------------------------------
// One service at a time
// ---------------------------------------------------------------------------------------------

function fetchSlice(
  service: AuditServiceKey,
  filters: AuditFilters,
  cursor: AuditCursor | null,
): Promise<AuditSlice> {
  return api.get<AuditSlice>(endpoints.audit.search(service), {
    params: {
      actor: filters.actor || undefined,
      action: filters.action || undefined,
      targetType: filters.targetType || undefined,
      targetId: filters.targetId || undefined,
      correlationId: filters.correlationId || undefined,
      from: filters.from || undefined,
      to: filters.to || undefined,
      beforeAt: cursor?.beforeAt,
      beforeId: cursor?.beforeId,
      size: PAGE_SIZE,
    },
  });
}

// ---------------------------------------------------------------------------------------------
// The merge
// ---------------------------------------------------------------------------------------------

/**
 * Asks every selected service for the next {@link PAGE_SIZE} entries after `cursor`, and returns
 * the newest {@link PAGE_SIZE} of what comes back.
 *
 * <h2>Why taking the top slice is safe</h2>
 *
 * Each service returns its own newest entries after the same position. Any entry not in the
 * combined top slice is older than the last one shown, so the next request — which starts from
 * exactly that position — will find it. Nothing can fall between two pages.
 *
 * <h2>Why some rows are fetched more than once</h2>
 *
 * A quiet service returns rows that are all older than the busy service's page, so none of them
 * are shown yet and the next request fetches them again. That is the cost of a single shared
 * position rather than five per-service ones, and it is bounded: at most five pages fetched per
 * page shown, on a screen an administrator opens occasionally.
 */
async function fetchMergedPage(
  filters: AuditFilters,
  cursor: AuditCursor | null,
): Promise<AuditPage> {
  const services = filters.services.length > 0
    ? filters.services
    : AUDIT_SERVICES.map((service) => service.key);

  // Each request carries its own service name through, rather than being matched back up by
  // position afterwards. A failure has to be attributable to the right service — the warning it
  // produces names it — and an index that drifts would name the wrong one.
  const results = await Promise.all(
    services.map(async (service) => {
      try {
        return { service, slice: await fetchSlice(service, filters, cursor) };
      } catch {
        return { service, slice: null };
      }
    }),
  );

  const entries: AuditEntry[] = [];
  const unavailable: AuditServiceKey[] = [];
  let anyServiceHasMore = false;

  results.forEach(({ service, slice }) => {
    if (slice) {
      entries.push(...slice.entries);
      anyServiceHasMore = anyServiceHasMore || slice.hasMore;
    } else {
      // One service being unreachable must not blank the screen: the other four still hold
      // evidence somebody may need right now. It is named instead, loudly.
      unavailable.push(service);
    }
  });

  entries.sort(compareNewestFirst);

  const visible = entries.slice(0, PAGE_SIZE);
  const last = visible[visible.length - 1];

  // Two independent reasons there is more to come, and both have to be checked.
  //
  // Either this merge held rows back, or some service said it had more of its own. Testing only
  // the first is wrong in an ordinary case: five services returning ten entries each is exactly
  // one page, nothing is held back, and every one of them may still have hundreds more. That
  // would end the trail early and silently, which is the one thing this screen must never do.
  const hasMore = anyServiceHasMore || entries.length > PAGE_SIZE;

  return {
    entries: visible,
    cursor: hasMore && last ? { beforeAt: last.occurredAt, beforeId: last.id } : null,
    unavailable,
  };
}

/**
 * Newest first, with the id as a tiebreak.
 *
 * The id is not meaningful ordering — the values are random — but it makes the sequence total, so
 * two entries written in the same instant keep a stable position between one page and the next
 * instead of swapping and appearing twice.
 */
function compareNewestFirst(left: AuditEntry, right: AuditEntry): number {
  if (left.occurredAt !== right.occurredAt) {
    return left.occurredAt < right.occurredAt ? 1 : -1;
  }
  return left.id < right.id ? 1 : -1;
}

// ---------------------------------------------------------------------------------------------
// Hooks
// ---------------------------------------------------------------------------------------------

export const auditKeys = {
  all: ['audit'] as const,
  trail: (filters: AuditFilters) => [...auditKeys.all, 'trail', filters] as const,
  actions: () => [...auditKeys.all, 'actions'] as const,
};

export function useAuditTrail(filters: AuditFilters) {
  return useInfiniteQuery({
    queryKey: auditKeys.trail(filters),
    queryFn: ({ pageParam }) => fetchMergedPage(filters, pageParam),
    initialPageParam: null as AuditCursor | null,
    getNextPageParam: (lastPage) => lastPage.cursor,
    // The trail is append-only and nobody is watching it for live changes; refetching on every
    // window focus would only re-run five requests while somebody reads.
    refetchOnWindowFocus: false,
  });
}

/**
 * Every action any service has recorded, for the filter.
 *
 * <p>Read from the services rather than hard-coded, so the list cannot drift out of step with what
 * the code actually audits. A service that fails to answer contributes nothing rather than
 * breaking the filter — the worst case is a dropdown missing an option, and the field still
 * accepts anything typed into it.
 */
export function useAuditActions() {
  return useQuery({
    queryKey: auditKeys.actions(),
    queryFn: async () => {
      const settled = await Promise.allSettled(
        AUDIT_SERVICES.map((service) => api.get<string[]>(endpoints.audit.actions(service.key))),
      );

      const actions = new Set<string>();
      settled.forEach((result) => {
        if (result.status === 'fulfilled') {
          result.value.forEach((action) => actions.add(action));
        }
      });

      return [...actions].sort();
    },
    staleTime: 5 * 60 * 1000,
  });
}

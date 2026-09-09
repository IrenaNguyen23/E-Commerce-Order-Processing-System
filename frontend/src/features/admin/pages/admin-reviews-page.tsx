import { CheckCircle2, MessageSquare, Star, X } from 'lucide-react';
import { useState } from 'react';

import { PageHeader } from '@/components/common/page-header';
import { EmptyState, ErrorState } from '@/components/common/states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import {
  useModerateReview,
  usePendingReviews,
  type Review,
} from '@/features/product/reviews-api';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { formatDateTime } from '@/utils/format';

/**
 * The moderation queue.
 *
 * <h2>Oldest first, and that is not cosmetic</h2>
 *
 * A queue worked newest-first leaves its tail permanently unread: the reviews that have waited
 * longest are exactly the ones a busy moderator never reaches. Oldest first means the wait is
 * bounded by throughput rather than by luck.
 *
 * <h2>Rejecting asks for a reason</h2>
 *
 * The reason is shown to the author and to nobody else — it is the only place they will ever find
 * out why their review is not appearing. A rejection with no note leaves somebody re-submitting the
 * same text and getting the same silence.
 *
 * <p>It is optional rather than mandatory, because obvious spam does not need explaining and
 * forcing a note there produces "spam" typed a hundred times, which helps nobody.
 */
export default function AdminReviewsPage() {
  const [page, setPage] = useState(0);
  const queue = usePendingReviews(page);
  const moderate = useModerateReview();

  const [rejecting, setRejecting] = useState<Review | null>(null);
  const [note, setNote] = useState('');

  const reviews = queue.data?.content ?? [];

  return (
    <div>
      <PageHeader
        title="Reviews"
        description="Reviews waiting to be checked, oldest first."
        breadcrumbs={[{ label: 'Admin', to: paths.admin.dashboard }, { label: 'Reviews' }]}
      />

      {queue.isError ? (
        <ErrorState
          error={normalizeError(queue.error)}
          onRetry={() => void queue.refetch()}
        />
      ) : queue.isLoading ? (
        <div className="space-y-3">
          {[0, 1, 2].map((row) => (
            <Skeleton key={row} className="h-32 w-full rounded-lg" />
          ))}
        </div>
      ) : reviews.length === 0 ? (
        <EmptyState
          icon={<MessageSquare className="h-10 w-10" />}
          title="Nothing waiting"
          description="Every review has been checked. New ones appear here as they are written."
        />
      ) : (
        <>
          <p className="mb-4 text-sm text-muted-foreground">
            {queue.data?.totalElements} waiting
          </p>

          <ul className="space-y-3">
            {reviews.map((review) => (
              <li key={review.id}>
                <Card>
                  <CardContent className="space-y-3 p-5">
                    <div className="flex flex-wrap items-center gap-2">
                      <Stars rating={review.rating} />
                      <span className="text-sm font-medium">{review.authorName}</span>

                      {/* The one signal worth reading before the text: a review from somebody who
                          actually bought the thing is a different kind of claim. */}
                      {review.verifiedPurchase ? (
                        <Badge variant="secondary" className="gap-1">
                          <CheckCircle2 className="h-3 w-3" aria-hidden />
                          Bought it
                        </Badge>
                      ) : (
                        <Badge variant="outline">No purchase on record</Badge>
                      )}

                      <span className="ml-auto text-xs text-muted-foreground">
                        {formatDateTime(review.createdAt)}
                      </span>
                    </div>

                    {review.title ? <p className="font-medium">{review.title}</p> : null}
                    {review.body ? (
                      <p className="whitespace-pre-line text-sm text-muted-foreground">
                        {review.body}
                      </p>
                    ) : (
                      <p className="text-sm italic text-muted-foreground">
                        A rating with no words.
                      </p>
                    )}

                    <div className="flex flex-wrap items-center gap-2 pt-1">
                      <Button
                        size="sm"
                        loading={moderate.isPending}
                        onClick={() =>
                          moderate.mutate({ reviewId: review.id, publish: true })
                        }
                      >
                        <CheckCircle2 aria-hidden />
                        Publish
                      </Button>

                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => {
                          setRejecting(review);
                          setNote('');
                        }}
                      >
                        <X aria-hidden />
                        Reject
                      </Button>

                      <Button variant="ghost" size="sm" asChild className="ml-auto">
                        <a
                          href={paths.product(review.productId)}
                          target="_blank"
                          rel="noreferrer noopener"
                        >
                          See the product
                        </a>
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              </li>
            ))}
          </ul>

          {(queue.data?.totalPages ?? 1) > 1 ? (
            <div className="mt-6 flex items-center justify-between">
              <Button
                variant="outline"
                size="sm"
                disabled={page === 0}
                onClick={() => setPage((current) => current - 1)}
              >
                Previous
              </Button>
              <span className="text-sm text-muted-foreground">
                Page {page + 1} of {queue.data?.totalPages}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={page + 1 >= (queue.data?.totalPages ?? 1)}
                onClick={() => setPage((current) => current + 1)}
              >
                Next
              </Button>
            </div>
          ) : null}
        </>
      )}

      <Dialog open={rejecting !== null} onOpenChange={(open) => !open && setRejecting(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Reject this review?</DialogTitle>
          </DialogHeader>

          <p className="text-sm text-muted-foreground">
            It stays on record and its author can see it, with whatever you write below. Nobody
            else ever sees either.
          </p>

          <div className="mt-4 space-y-2">
            <Label htmlFor="reject-note">Reason (optional)</Label>
            <Input
              id="reject-note"
              maxLength={255}
              placeholder="Names another customer"
              value={note}
              onChange={(event) => setNote(event.target.value)}
            />
            {/* Optional on purpose: obvious spam needs no explanation, and forcing a note there
                produces "spam" typed a hundred times. */}
            <p className="text-xs text-muted-foreground">
              This is the only place the author will find out why. Worth writing unless it is
              plainly spam.
            </p>
          </div>

          <DialogFooter className="gap-2">
            <Button variant="ghost" onClick={() => setRejecting(null)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              loading={moderate.isPending}
              onClick={() => {
                if (rejecting) {
                  moderate.mutate({
                    reviewId: rejecting.id,
                    publish: false,
                    note: note.trim() || undefined,
                  });
                }
                setRejecting(null);
              }}
            >
              Reject
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Stars({ rating }: { rating: number }) {
  return (
    <span className="flex items-center gap-0.5" aria-label={`${rating} out of 5`}>
      {[1, 2, 3, 4, 5].map((star) => (
        <Star
          key={star}
          aria-hidden
          className={
            star <= rating
              ? 'h-4 w-4 fill-amber-400 text-amber-400'
              : 'h-4 w-4 text-muted-foreground/30'
          }
        />
      ))}
    </span>
  );
}

import { CheckCircle2, Star } from 'lucide-react';
import { useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { selectIsAuthenticated, useAuthStore } from '@/features/auth/store';
import { formatDateTime } from '@/utils/format';

import { useMyReviews, useProductReviews, useSubmitReview, type Review } from '../reviews-api';

/**
 * What customers thought, and a form to add to it.
 *
 * <h2>The author sees their own review even when nobody else can</h2>
 *
 * New reviews wait for a moderator by default. A page that simply does not show somebody's own
 * review after they submitted it looks like the submission failed, and they write it again. So a
 * pending review is rendered to its author, marked as waiting — and a rejected one is rendered
 * too, with the reason, because that is the only place the reason is ever shown.
 */
export function ProductReviews({ productId }: { productId: string }) {
  const reviews = useProductReviews(productId);
  const mine = useMyReviews();
  const isAuthenticated = useAuthStore(selectIsAuthenticated);

  const myReview = mine.data?.find((review) => review.productId === productId) ?? null;
  const published = reviews.data?.content ?? [];

  // The author's own review, when it is not in the published list. Not deduplicated by id alone:
  // a published review appears in both, and showing it twice is worse than not showing the badge.
  const showMineSeparately =
    myReview !== null && !published.some((review) => review.id === myReview.id);

  return (
    <section className="space-y-6" aria-labelledby="reviews-heading">
      <div className="flex items-baseline justify-between gap-4">
        <h2 id="reviews-heading" className="text-lg font-semibold">
          Reviews
        </h2>
        {reviews.data ? (
          <span className="text-sm text-muted-foreground">
            {reviews.data.totalElements === 0
              ? 'None yet'
              : `${reviews.data.totalElements} review${reviews.data.totalElements === 1 ? '' : 's'}`}
          </span>
        ) : null}
      </div>

      {showMineSeparately && myReview ? (
        <ReviewCard review={myReview} isMine />
      ) : null}

      {published.length === 0 && !showMineSeparately ? (
        <p className="text-sm text-muted-foreground">
          Nobody has reviewed this yet. If you have one, yours will be the first.
        </p>
      ) : (
        <ul className="space-y-4">
          {published.map((review) => (
            <li key={review.id}>
              <ReviewCard review={review} isMine={review.id === myReview?.id} />
            </li>
          ))}
        </ul>
      )}

      {isAuthenticated ? (
        <ReviewForm productId={productId} existing={myReview} />
      ) : (
        <p className="text-sm text-muted-foreground">
          Sign in to write a review.
        </p>
      )}
    </section>
  );
}

function ReviewCard({ review, isMine }: { review: Review; isMine: boolean }) {
  return (
    <Card className={isMine ? 'border-primary/30' : undefined}>
      <CardContent className="space-y-2 p-5">
        <div className="flex flex-wrap items-center gap-2">
          <Stars rating={review.rating} />
          <span className="text-sm font-medium">{review.authorName}</span>

          {review.verifiedPurchase ? (
            <Badge variant="secondary" className="gap-1">
              <CheckCircle2 className="h-3 w-3" aria-hidden />
              Verified purchase
            </Badge>
          ) : null}

          {isMine && review.status === 'PENDING' ? (
            <Badge variant="outline">Waiting to be checked</Badge>
          ) : null}
          {isMine && review.status === 'REJECTED' ? (
            <Badge variant="destructive">Not published</Badge>
          ) : null}

          <span className="ml-auto text-xs text-muted-foreground">
            {formatDateTime(review.createdAt)}
          </span>
        </div>

        {review.title ? <p className="font-medium">{review.title}</p> : null}
        {review.body ? (
          <p className="whitespace-pre-line text-sm text-muted-foreground">{review.body}</p>
        ) : null}

        {/* The only place a rejection reason is ever shown, and only to its author. */}
        {isMine && review.status === 'REJECTED' && review.moderationNote ? (
          <p className="rounded-md bg-destructive/5 p-3 text-sm text-destructive">
            {review.moderationNote}
          </p>
        ) : null}
      </CardContent>
    </Card>
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

/**
 * Writing a review.
 *
 * Pre-filled from the author's existing one, because submitting again edits it rather than adding
 * a second — a blank form there would look like a way to write another and quietly overwrite the
 * first.
 */
function ReviewForm({ productId, existing }: { productId: string; existing: Review | null }) {
  const submit = useSubmitReview(productId);

  const [rating, setRating] = useState(existing?.rating ?? 0);
  const [title, setTitle] = useState(existing?.title ?? '');
  const [body, setBody] = useState(existing?.body ?? '');
  const [authorName, setAuthorName] = useState(existing?.authorName ?? '');

  return (
    <Card>
      <CardContent className="space-y-4 p-5">
        <h3 className="font-medium">{existing ? 'Edit your review' : 'Write a review'}</h3>

        {existing ? (
          <p className="text-sm text-muted-foreground">
            Editing sends it back to be checked before it appears again.
          </p>
        ) : null}

        <fieldset>
          <legend className="text-sm font-medium">Your rating</legend>
          <div className="mt-2 flex gap-1">
            {[1, 2, 3, 4, 5].map((star) => (
              <button
                key={star}
                type="button"
                onClick={() => setRating(star)}
                aria-label={`${star} star${star === 1 ? '' : 's'}`}
                aria-pressed={rating === star}
                className="rounded p-0.5 focus-visible:outline focus-visible:outline-2"
              >
                <Star
                  aria-hidden
                  className={
                    star <= rating
                      ? 'h-6 w-6 fill-amber-400 text-amber-400'
                      : 'h-6 w-6 text-muted-foreground/40'
                  }
                />
              </button>
            ))}
          </div>
        </fieldset>

        <div>
          <Label htmlFor="review-title">Headline (optional)</Label>
          <Input
            id="review-title"
            className="mt-2"
            maxLength={150}
            value={title}
            onChange={(event) => setTitle(event.target.value)}
          />
        </div>

        <div>
          <Label htmlFor="review-body">Your review (optional)</Label>
          <Textarea
            id="review-body"
            className="mt-2"
            rows={4}
            maxLength={4000}
            value={body}
            onChange={(event) => setBody(event.target.value)}
          />
        </div>

        <div>
          <Label htmlFor="review-author">Show my name as (optional)</Label>
          <Input
            id="review-author"
            className="mt-2"
            maxLength={100}
            placeholder="Ada L."
            value={authorName}
            onChange={(event) => setAuthorName(event.target.value)}
          />
          {/* Said out loud, because the alternative people fear is their email appearing. */}
          <p className="mt-1 text-xs text-muted-foreground">
            Left blank, only your initial is shown. Your email address is never published.
          </p>
        </div>

        <Button
          type="button"
          loading={submit.isPending}
          disabled={rating < 1 || submit.isPending}
          onClick={() =>
            submit.mutate({
              rating,
              title: title.trim() || undefined,
              body: body.trim() || undefined,
              authorName: authorName.trim() || undefined,
            })
          }
        >
          {existing ? 'Save changes' : 'Submit review'}
        </Button>
      </CardContent>
    </Card>
  );
}

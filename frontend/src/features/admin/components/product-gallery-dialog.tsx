import { ImageOff, Star, Trash2, Upload } from 'lucide-react';
import { useRef, useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import type { Product } from '@/features/product/types';

import {
  useDeleteProductImage,
  useMakeImagePrimary,
  useProductImages,
  useUploadProductImage,
} from '../product-images-api';

/** Two megabytes, matching the server. Checked here so a rejection costs no upload. */
const MAX_BYTES = 2 * 1024 * 1024;

/**
 * A product's photographs.
 *
 * <h2>What the server will refuse, said before the upload</h2>
 *
 * JPEG, PNG or WebP, under two megabytes, and **the type is decided from the bytes** rather than
 * from the file's name or the type the browser reports. That is worth saying on the form, because
 * otherwise the failure reads as arbitrary: renaming a `.gif` to `.png` changes nothing, and
 * somebody will try.
 *
 * <p>The size check is repeated here purely so a rejection is instant. The server's is the one that
 * counts — a client-side limit is a courtesy, never a control.
 *
 * <h2>The first upload becomes the tile</h2>
 *
 * Automatically, so a product never ends up with a gallery and no thumbnail. Which one is the tile
 * can be changed afterwards, and the badge says which it currently is.
 */
export function ProductGalleryDialog({
  product,
  onClose,
}: {
  product: Product | null;
  onClose: () => void;
}) {
  const productId = product?.id ?? '';
  const images = useProductImages(product?.id);
  const upload = useUploadProductImage(productId);
  const makePrimary = useMakeImagePrimary(productId);
  const remove = useDeleteProductImage(productId);

  const fileInput = useRef<HTMLInputElement>(null);
  const [altText, setAltText] = useState('');
  const [tooLarge, setTooLarge] = useState<string | null>(null);

  const submit = (file: File) => {
    if (file.size > MAX_BYTES) {
      setTooLarge(
        `That file is ${Math.round(file.size / 1024)} kB. The limit is ${MAX_BYTES / 1024} kB.`,
      );
      return;
    }
    setTooLarge(null);
    upload.mutate(
      { file, altText: altText.trim() || undefined },
      { onSuccess: () => setAltText('') },
    );
  };

  return (
    <Dialog open={product !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Images — {product?.name}</DialogTitle>
        </DialogHeader>

        <div className="space-y-6">
          {images.isLoading ? (
            <div className="grid grid-cols-3 gap-3">
              {[0, 1, 2].map((slot) => (
                <Skeleton key={slot} className="aspect-square rounded-md" />
              ))}
            </div>
          ) : (images.data?.length ?? 0) === 0 ? (
            <div className="flex flex-col items-center gap-2 rounded-md border border-dashed p-8 text-center">
              <ImageOff className="h-8 w-8 text-muted-foreground" aria-hidden />
              <p className="text-sm text-muted-foreground">
                No uploads yet. Until there is one, the product shows whatever image URL was set on
                it directly.
              </p>
            </div>
          ) : (
            <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
              {images.data?.map((image) => (
                <li key={image.id} className="group relative">
                  <img
                    src={image.url}
                    alt={image.altText ?? ''}
                    loading="lazy"
                    className="aspect-square w-full rounded-md border object-cover"
                  />

                  {image.primary ? (
                    <Badge className="absolute left-2 top-2 gap-1">
                      <Star className="h-3 w-3 fill-current" aria-hidden />
                      Tile
                    </Badge>
                  ) : null}

                  <div className="mt-2 flex items-center justify-between gap-1">
                    <span className="text-xs text-muted-foreground">
                      {Math.round(image.sizeBytes / 1024)} kB
                    </span>

                    <div className="flex gap-1">
                      {!image.primary ? (
                        <Button
                          variant="ghost"
                          size="icon"
                          aria-label="Make this the tile image"
                          title="Make this the tile image"
                          onClick={() => makePrimary.mutate(image.id)}
                        >
                          <Star aria-hidden />
                        </Button>
                      ) : null}

                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label="Delete this image"
                        onClick={() => remove.mutate(image.id)}
                      >
                        <Trash2 aria-hidden />
                      </Button>
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}

          <div className="space-y-3 rounded-md border p-4">
            <div>
              <Label htmlFor="image-alt">Description for screen readers (optional)</Label>
              <Input
                id="image-alt"
                className="mt-2"
                maxLength={255}
                placeholder="A laptop stand, from the side"
                value={altText}
                onChange={(event) => setAltText(event.target.value)}
              />
              {/* Optional, and honestly so: an empty alt attribute is correct for a purely
                  decorative image, and forcing text produces "image1.jpg" read aloud. */}
              <p className="mt-1 text-xs text-muted-foreground">
                Left blank, the image is treated as decorative.
              </p>
            </div>

            <input
              ref={fileInput}
              type="file"
              accept="image/jpeg,image/png,image/webp"
              className="hidden"
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (file) {
                  submit(file);
                }
                // Cleared so choosing the same file twice fires a change event the second time.
                event.target.value = '';
              }}
            />

            <Button
              type="button"
              variant="outline"
              loading={upload.isPending}
              onClick={() => fileInput.current?.click()}
            >
              <Upload aria-hidden />
              Upload an image
            </Button>

            {tooLarge ? (
              <p className="text-sm text-destructive" role="alert">
                {tooLarge}
              </p>
            ) : null}

            {/* Said before the upload rather than after the refusal, because otherwise the
                rejection reads as arbitrary — and somebody will try renaming a .gif. */}
            <p className="text-xs text-muted-foreground">
              JPEG, PNG or WebP, up to {MAX_BYTES / 1024} kB. The type is checked by reading the
              file, not its name — renaming will not help. SVG is not accepted: it can contain
              script, and serving one from this domain would run it here.
            </p>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

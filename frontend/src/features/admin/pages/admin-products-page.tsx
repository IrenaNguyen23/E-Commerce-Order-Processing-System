import { zodResolver } from '@hookform/resolvers/zod';
import { Image as ImageIcon, ImageOff, Package, Plus } from 'lucide-react';
import { useCallback, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { DataTable, type Column } from '@/components/common/data-table';
import { PageHeader } from '@/components/common/page-header';
import { PagePagination } from '@/components/common/pagination';
import { SearchInput } from '@/components/common/search-input';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { useCreateProduct, useProducts, useUpdateProduct } from '@/features/product/hooks';
import { stockLevel, type Product } from '@/features/product/types';
import { useQueryParams } from '@/hooks/use-query-params';

import { ProductGalleryDialog } from '../components/product-gallery-dialog';
import { normalizeError, toFieldErrorMap } from '@/services/error';
import { ADMIN_PAGE_SIZE } from '@/shared/config';
import { formatDate, formatMoney } from '@/utils/format';

const DEFAULTS = {
  page: 0,
  size: ADMIN_PAGE_SIZE,
  search: '',
  sortBy: 'createdAt',
  direction: 'desc' as 'asc' | 'desc',
};

/** Mirrors the backend's `CreateProductRequest` constraints so errors surface before the request. */
const createProductSchema = z.object({
  sku: z
    .string()
    .min(1, 'SKU is required')
    .max(64, 'SKU is too long')
    .regex(/^[A-Za-z0-9._-]+$/, 'Letters, digits, dot, dash and underscore only'),
  name: z.string().min(1, 'Name is required').max(200, 'Name is too long'),
  description: z.string().max(2000, 'Description is too long').optional().or(z.literal('')),
  category: z.string().max(100, 'Category is too long').optional().or(z.literal('')),
  price: z.coerce.number().positive('Price must be greater than zero'),
  currency: z
    .string()
    .regex(/^[A-Z]{3}$/, 'Use a three-letter ISO code, e.g. EUR')
    .optional()
    .or(z.literal('')),
  imageUrl: z.string().max(500).optional().or(z.literal('')),
  initialQuantity: z.coerce.number().int().min(0, 'Cannot be negative'),
  reorderLevel: z.coerce.number().int().min(0, 'Cannot be negative').optional(),
});

type CreateProductValues = z.infer<typeof createProductSchema>;

export default function AdminProductsPage() {
  const { params, setParams } = useQueryParams(DEFAULTS);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Product | null>(null);

  const query = useProducts({
    page: params.page,
    size: params.size,
    search: params.search || undefined,
    // Admin needs to see deactivated products too — that is the whole point of the console.
    activeOnly: false,
    sortBy: params.sortBy,
    direction: params.direction,
  });

  const handleSearch = useCallback(
    (search: string) => setParams({ search, page: 0 }),
    [setParams],
  );

  const [managingImages, setManagingImages] = useState<Product | null>(null);

  const columns: Column<Product>[] = [
    {
      id: 'product',
      header: 'Product',
      cell: (product) => (
        <div className="flex items-center gap-3">
          <div className="h-10 w-10 shrink-0 overflow-hidden rounded border bg-muted">
            {product.imageUrl ? (
              <img
                src={product.imageUrl}
                alt=""
                loading="lazy"
                className="h-full w-full object-cover"
              />
            ) : (
              <div className="flex h-full items-center justify-center text-muted-foreground">
                <ImageOff className="h-4 w-4" aria-hidden />
              </div>
            )}
          </div>
          <div className="min-w-0">
            <p className="truncate font-medium">{product.name}</p>
            <p className="font-mono text-xs text-muted-foreground">{product.sku}</p>
          </div>
        </div>
      ),
    },
    {
      id: 'category',
      header: 'Category',
      hideOnMobile: true,
      cell: (product) =>
        product.category ? (
          <span className="text-sm">{product.category}</span>
        ) : (
          <span className="text-sm text-muted-foreground">—</span>
        ),
    },
    {
      id: 'price',
      header: 'Price',
      className: 'text-right',
      cell: (product) => (
        <span className="tabular">{formatMoney(product.price, product.currency)}</span>
      ),
    },
    {
      id: 'stock',
      header: 'Stock',
      className: 'text-right',
      cell: (product) => {
        const level = stockLevel(product);
        return (
          <div className="flex items-center justify-end gap-2">
            <span className="tabular">{product.availableQuantity}</span>
            {product.reservedQuantity > 0 ? (
              <span className="text-xs text-muted-foreground tabular">
                (+{product.reservedQuantity} held)
              </span>
            ) : null}
            {level === 'out' ? (
              <Badge variant="destructive">Out</Badge>
            ) : level === 'low' ? (
              <Badge variant="warning">Low</Badge>
            ) : null}
          </div>
        );
      },
    },
    {
      id: 'status',
      header: 'Status',
      hideOnMobile: true,
      cell: (product) =>
        product.active ? (
          <Badge variant="success">Active</Badge>
        ) : (
          <Badge variant="secondary">Inactive</Badge>
        ),
    },
    {
      id: 'created',
      header: 'Added',
      hideOnMobile: true,
      cell: (product) => (
        <span className="text-sm text-muted-foreground">{formatDate(product.createdAt)}</span>
      ),
    },
    {
      id: 'actions',
      header: '',
      className: 'text-right',
      cell: (product) => (
        <div className="flex justify-end gap-1">
          <Button
            variant="ghost"
            size="icon"
            aria-label={`Images for ${product.name}`}
            title="Images"
            onClick={() => setManagingImages(product)}
          >
            <ImageIcon aria-hidden />
          </Button>
          <Button variant="outline" size="sm" onClick={() => setEditing(product)}>
            Edit
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Products"
        description="The catalogue, including deactivated products."
        breadcrumbs={[{ label: 'Admin' }, { label: 'Products' }]}
        actions={
          <Button onClick={() => setDialogOpen(true)}>
            <Plus aria-hidden />
            Add product
          </Button>
        }
      />

      <div className="space-y-4">
        <SearchInput
          value={params.search}
          onChange={handleSearch}
          placeholder="Search by name or SKU…"
          className="max-w-sm"
        />

        <DataTable
          columns={columns}
          rows={query.data?.content}
          getRowId={(product) => product.id}
          isLoading={query.isLoading}
          error={query.error ? normalizeError(query.error) : null}
          onRetry={() => void query.refetch()}
          skeletonRows={8}
          emptyTitle={params.search ? 'No products match that search' : 'No products yet'}
          emptyDescription={
            params.search
              ? 'Try a different name or SKU.'
              : 'Add the first product to get the catalogue started.'
          }
          emptyAction={
            <Button onClick={() => setDialogOpen(true)}>
              <Plus aria-hidden />
              Add product
            </Button>
          }
        />

        <PagePagination data={query.data} onPageChange={(page) => setParams({ page })} />
      </div>

      <CreateProductDialog open={dialogOpen} onOpenChange={setDialogOpen} />
      <EditProductDialog product={editing} onClose={() => setEditing(null)} />
      <ProductGalleryDialog
        product={managingImages}
        onClose={() => setManagingImages(null)}
      />
    </div>
  );
}

function CreateProductDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const createProduct = useCreateProduct();

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<CreateProductValues>({
    resolver: zodResolver(createProductSchema),
    defaultValues: {
      sku: '',
      name: '',
      description: '',
      category: '',
      price: 0,
      currency: 'EUR',
      imageUrl: '',
      initialQuantity: 0,
      reorderLevel: 0,
    },
  });

  const onSubmit = handleSubmit(async (values) => {
    try {
      await createProduct.mutateAsync({
        sku: values.sku,
        name: values.name,
        description: values.description || undefined,
        // A slug, and the section has to exist first — so a typo is an error rather than
        // a fourth spelling of Computers appearing in the catalogue.
        categorySlug: values.category || undefined,
        price: values.price,
        currency: values.currency || undefined,
        imageUrl: values.imageUrl || undefined,
        initialQuantity: values.initialQuantity,
        reorderLevel: values.reorderLevel,
      });
      reset();
      onOpenChange(false);
    } catch (error) {
      // A duplicate SKU comes back as 409 with no fieldErrors — put it on the SKU field anyway,
      // which is where the operator is looking.
      const appError = normalizeError(error);
      const fieldErrors = toFieldErrorMap(appError);
      Object.entries(fieldErrors).forEach(([field, message]) => {
        setError(field as keyof CreateProductValues, { type: 'server', message });
      });
      if (appError.code === 'SKU_ALREADY_EXISTS') {
        setError('sku', { type: 'server', message: appError.message });
      }
    }
  });

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) reset();
        onOpenChange(next);
      }}
    >
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Add a product</DialogTitle>
        </DialogHeader>

        <form onSubmit={onSubmit} className="grid gap-4 sm:grid-cols-2" noValidate>
          <AdminField
            id="sku"
            label="SKU"
            placeholder="CF-LAPTOP-001"
            error={errors.sku?.message}
            {...register('sku')}
          />
          <AdminField
            id="category"
            label="Category"
            optional
            placeholder="COMPUTERS"
            error={errors.category?.message}
            {...register('category')}
          />
          <AdminField
            id="name"
            label="Name"
            className="sm:col-span-2"
            error={errors.name?.message}
            {...register('name')}
          />

          <div className="sm:col-span-2">
            <Label htmlFor="description">
              Description <span className="text-muted-foreground">(optional)</span>
            </Label>
            <Textarea id="description" className="mt-2" rows={3} {...register('description')} />
            {errors.description ? (
              <p className="mt-1 text-sm text-destructive">{errors.description.message}</p>
            ) : null}
          </div>

          <AdminField
            id="price"
            label="Price"
            type="number"
            step="0.01"
            min="0"
            error={errors.price?.message}
            {...register('price')}
          />
          <AdminField
            id="currency"
            label="Currency"
            placeholder="EUR"
            error={errors.currency?.message}
            {...register('currency')}
          />
          <AdminField
            id="initialQuantity"
            label="Opening stock"
            type="number"
            min="0"
            error={errors.initialQuantity?.message}
            {...register('initialQuantity')}
          />
          <AdminField
            id="reorderLevel"
            label="Reorder level"
            type="number"
            min="0"
            error={errors.reorderLevel?.message}
            {...register('reorderLevel')}
          />
          <AdminField
            id="imageUrl"
            label="Image URL"
            optional
            className="sm:col-span-2"
            placeholder="https://…"
            error={errors.imageUrl?.message}
            {...register('imageUrl')}
          />

          <DialogFooter className="sm:col-span-2">
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={createProduct.isPending}>
              <Package aria-hidden />
              Create product
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

const AdminField = ({
  id,
  label,
  error,
  optional,
  className,
  ...props
}: React.InputHTMLAttributes<HTMLInputElement> & {
  id: string;
  label: string;
  error?: string;
  optional?: boolean;
}) => (
  <div className={className}>
    <Label htmlFor={id}>
      {label}
      {optional ? <span className="ml-1 text-muted-foreground">(optional)</span> : null}
    </Label>
    <Input id={id} className="mt-2" aria-invalid={Boolean(error)} {...props} />
    {error ? <p className="mt-1 text-sm text-destructive">{error}</p> : null}
  </div>
);

/**
 * Editing a product, and the two fields that are not here.
 *
 * **SKU** is absent because it is how warehouses, invoices and every integration refer to this
 * product; changing it would silently break all of them. A product that needs a different SKU is
 * a different product.
 *
 * **Stock** is absent because it has its own rules — the stock endpoint only moves
 * `availableQuantity` and never touches units a running order is holding. Editing it as an
 * ordinary field here would let a back-office correction quietly overwrite a live reservation.
 *
 * There is no delete either. Orders reference products, and a customer has a receipt for what
 * they bought; taking something off sale is what "active" is for.
 */
function EditProductDialog({
  product,
  onClose,
}: {
  product: Product | null;
  onClose: () => void;
}) {
  const updateProduct = useUpdateProduct();

  const [form, setForm] = useState({
    name: '',
    description: '',
    category: '',
    price: '',
    imageUrl: '',
    active: true,
  });

  // Re-seed whenever a different product is opened, so one edit never carries into the next.
  const [openedFor, setOpenedFor] = useState<string | null>(null);
  if (product && openedFor !== product.id) {
    setOpenedFor(product.id);
    setForm({
      name: product.name,
      description: product.description ?? '',
      category: product.categorySlug ?? '',
      price: String(product.price),
      imageUrl: product.imageUrl ?? '',
      active: product.active,
    });
  }

  if (!product) {
    return null;
  }

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    await updateProduct.mutateAsync({
      id: product.id,
      payload: {
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        categorySlug: form.category.trim() || undefined,
        price: Number(form.price),
        imageUrl: form.imageUrl.trim() || undefined,
        active: form.active,
      },
    });
    onClose();
  };

  return (
    <Dialog open={Boolean(product)} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Edit product</DialogTitle>
        </DialogHeader>

        <form onSubmit={onSubmit} className="space-y-4">
          <p className="font-mono text-xs text-muted-foreground">
            {product.sku} &middot; SKU and stock are changed elsewhere
          </p>

          <div className="space-y-2">
            <Label htmlFor="edit-name">Name</Label>
            <Input
              id="edit-name"
              required
              maxLength={200}
              value={form.name}
              onChange={(event) => setForm({ ...form, name: event.target.value })}
            />
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="edit-price">Price</Label>
              <Input
                id="edit-price"
                type="number"
                step="0.01"
                min="0.01"
                required
                value={form.price}
                onChange={(event) => setForm({ ...form, price: event.target.value })}
              />
              {/* Worth saying out loud: an operator correcting a typo should not fear rewriting
                  what somebody already paid. */}
              <p className="text-xs text-muted-foreground">
                Existing orders keep the price they were bought at.
              </p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="edit-category">Category</Label>
              <Input
                id="edit-category"
                maxLength={100}
                value={form.category}
                onChange={(event) => setForm({ ...form, category: event.target.value })}
              />
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="edit-description">Description</Label>
            <Input
              id="edit-description"
              maxLength={2000}
              value={form.description}
              onChange={(event) => setForm({ ...form, description: event.target.value })}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="edit-image">Image URL</Label>
            <Input
              id="edit-image"
              maxLength={500}
              value={form.imageUrl}
              onChange={(event) => setForm({ ...form, imageUrl: event.target.value })}
            />
          </div>

          <div className="flex items-start gap-3 rounded-md border bg-muted/40 p-3">
            <input
              id="edit-active"
              type="checkbox"
              checked={form.active}
              onChange={(event) => setForm({ ...form, active: event.target.checked })}
              className="mt-0.5 h-4 w-4"
            />
            <div>
              <Label htmlFor="edit-active">On sale</Label>
              <p className="text-xs text-muted-foreground">
                Unchecked hides it from the storefront and refuses new orders for it. Existing
                orders are unaffected.
              </p>
            </div>
          </div>

          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" loading={updateProduct.isPending}>
              Save changes
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

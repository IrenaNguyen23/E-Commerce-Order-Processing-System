import { FolderTree, Pencil, Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';

import { ConfirmDialog } from '@/components/common/confirm-dialog';
import { PageHeader } from '@/components/common/page-header';
import { DataTable, type Column } from '@/components/common/data-table';
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
import { Checkbox } from '@/components/ui/checkbox';
import { Textarea } from '@/components/ui/textarea';
import {
  useCreateCategory,
  useDeleteCategory,
  useUpdateCategory,
  type Category,
} from '@/features/product/categories-api';
import { useCategories } from '@/features/product/hooks';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';

/**
 * Catalogue sections.
 *
 * <p>This page used to say there was nothing to manage, because a category was a free-text column
 * on a product. There is a table now, and the difference is worth knowing while using this screen:
 * a rename here changes one row rather than every product that happened to spell it the same way.
 *
 * <h2>The slug is shown and cannot be edited</h2>
 *
 * <p>Shown because it is what URLs and filters use, and a merchandiser renaming a section should
 * be able to see that the link they gave somebody still works. Not editable because tax rates are
 * looked up by it — changing a slug would change what the products inside are charged, with no
 * error anywhere and a different figure on the next order.
 */
export default function AdminCategoriesPage() {
  const query = useCategories(true);
  const createCategory = useCreateCategory();
  const updateCategory = useUpdateCategory();
  const deleteCategory = useDeleteCategory();

  const [editing, setEditing] = useState<Category | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<Category | null>(null);

  const columns: Column<Category>[] = [
    {
      id: 'name',
      header: 'Section',
      cell: (row) => (
        <div className="min-w-0">
          <span className="font-medium">{row.name}</span>
          {/* The identifier the rest of the world uses. Visible so a rename is obviously not a
              re-address. */}
          <span className="block font-mono text-xs text-muted-foreground">{row.slug}</span>
        </div>
      ),
    },
    {
      id: 'products',
      header: 'Products',
      className: 'text-right',
      cell: (row) => <span className="tabular">{row.productCount}</span>,
    },
    {
      id: 'position',
      header: 'Order',
      className: 'text-right',
      cell: (row) => <span className="tabular text-muted-foreground">{row.position}</span>,
    },
    {
      id: 'active',
      header: 'Shown',
      cell: (row) =>
        row.active ? (
          <Badge variant="secondary">On the storefront</Badge>
        ) : (
          <Badge variant="outline">Hidden</Badge>
        ),
    },
    {
      id: 'actions',
      header: '',
      className: 'text-right',
      cell: (row) => (
        <div className="flex justify-end gap-1">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setEditing(row)}
            aria-label={`Edit ${row.name}`}
          >
            <Pencil aria-hidden />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            // Disabled rather than hidden, with the reason in the title: an operator who cannot
            // find the delete button assumes it is missing, not that it does not apply.
            disabled={row.productCount > 0}
            title={
              row.productCount > 0
                ? `${row.productCount} product(s) are filed here. Move them, or hide the section instead.`
                : undefined
            }
            onClick={() => setDeleting(row)}
            aria-label={`Delete ${row.name}`}
          >
            <Trash2 aria-hidden />
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Categories"
        description="Sections of the catalogue, in the order they appear."
        breadcrumbs={[{ label: 'Admin', to: paths.admin.dashboard }, { label: 'Categories' }]}
        actions={
          <Button onClick={() => setCreating(true)}>
            <Plus aria-hidden />
            Add section
          </Button>
        }
      />

      <DataTable
        columns={columns}
        rows={query.data}
        getRowId={(row) => row.id}
        isLoading={query.isLoading}
        error={query.error ? normalizeError(query.error) : null}
        onRetry={() => void query.refetch()}
        skeletonRows={5}
        emptyTitle="No sections yet"
        emptyDescription="Add one, then file products under it."
        emptyAction={
          <Button onClick={() => setCreating(true)}>
            <Plus aria-hidden />
            Add section
          </Button>
        }
      />

      <CategoryDialog
        open={creating || editing !== null}
        category={editing}
        categories={query.data ?? []}
        pending={createCategory.isPending || updateCategory.isPending}
        onClose={() => {
          setCreating(false);
          setEditing(null);
        }}
        onSubmit={(values) => {
          if (editing) {
            updateCategory.mutate({ id: editing.id, payload: values });
          } else {
            createCategory.mutate(values);
          }
          setCreating(false);
          setEditing(null);
        }}
      />

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`Delete ${deleting?.name ?? 'this section'}?`}
        description={
          <>
            <span className="block">
              Only an empty section can be removed. Orders placed while it existed are unaffected —
              each one copied the category name it showed.
            </span>
            <span className="mt-2 block">
              If you only want it off the storefront, edit it and turn “Shown” off instead.
            </span>
          </>
        }
        confirmLabel="Delete"
        destructive
        onConfirm={() => {
          if (deleting) {
            deleteCategory.mutate(deleting.id);
          }
          setDeleting(null);
        }}
      />
    </div>
  );
}

function CategoryDialog({
  open,
  category,
  categories,
  pending,
  onClose,
  onSubmit,
}: {
  open: boolean;
  category: Category | null;
  categories: Category[];
  pending: boolean;
  onClose: () => void;
  onSubmit: (values: {
    name: string;
    slug?: string;
    description?: string;
    parentId?: string;
    position?: number;
    active?: boolean;
  }) => void;
}) {
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [description, setDescription] = useState('');
  const [position, setPosition] = useState('0');
  const [active, setActive] = useState(true);

  // Reset from the row being edited whenever the dialog opens. Keyed on the id so opening a
  // different section does not show the previous one's values for a frame.
  const [loadedFor, setLoadedFor] = useState<string | null>(null);
  const key = category?.id ?? '__new__';
  if (open && loadedFor !== key) {
    setLoadedFor(key);
    setName(category?.name ?? '');
    setSlug(category?.slug ?? '');
    setDescription(category?.description ?? '');
    setPosition(String(category?.position ?? 0));
    setActive(category?.active ?? true);
  }

  const parents = categories.filter(
    (candidate) => candidate.parentId === null && candidate.id !== category?.id,
  );

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          setLoadedFor(null);
          onClose();
        }
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <FolderTree className="h-4 w-4 text-muted-foreground" aria-hidden />
            {category ? 'Edit section' : 'Add section'}
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <div>
            <Label htmlFor="category-name">Name</Label>
            <Input
              id="category-name"
              className="mt-2"
              maxLength={100}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
            <p className="mt-1 text-xs text-muted-foreground">
              What customers read. Safe to change at any time.
            </p>
          </div>

          <div>
            <Label htmlFor="category-slug">Slug</Label>
            <Input
              id="category-slug"
              className="mt-2 font-mono text-sm"
              maxLength={100}
              placeholder="derived from the name"
              value={slug}
              disabled={category !== null}
              onChange={(event) => setSlug(event.target.value)}
            />
            <p className="mt-1 text-xs text-muted-foreground">
              {category
                ? 'Cannot be changed: URLs, saved filters and the tax rate card all key on it.'
                : 'Leave blank to derive it from the name. It cannot be changed afterwards.'}
            </p>
          </div>

          <div>
            <Label htmlFor="category-description">Description (optional)</Label>
            <Textarea
              id="category-description"
              className="mt-2"
              rows={2}
              maxLength={500}
              value={description}
              onChange={(event) => setDescription(event.target.value)}
            />
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <Label htmlFor="category-position">Menu order</Label>
              <Input
                id="category-position"
                type="number"
                className="mt-2"
                value={position}
                onChange={(event) => setPosition(event.target.value)}
              />
              <p className="mt-1 text-xs text-muted-foreground">Lower appears first.</p>
            </div>

            <div className="flex items-end pb-2">
              <label className="flex items-center gap-2 text-sm">
                <Checkbox
                  checked={active}
                  onCheckedChange={(checked) => setActive(checked === true)}
                />
                Shown on the storefront
              </label>
            </div>
          </div>

          {parents.length > 0 && !category?.children.length ? (
            <p className="text-xs text-muted-foreground">
              Sections nest one level deep. To make this a subsection, set its parent from the
              catalogue API — the form keeps it simple deliberately.
            </p>
          ) : null}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            loading={pending}
            disabled={!name.trim() || pending}
            onClick={() =>
              onSubmit({
                name: name.trim(),
                slug: category ? undefined : slug.trim() || undefined,
                description: description.trim() || undefined,
                position: Number(position) || 0,
                active,
              })
            }
          >
            {category ? 'Save changes' : 'Add section'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { ShieldCheck, User as UserIcon } from 'lucide-react';
import { useState } from 'react';

import { DataTable, type Column } from '@/components/common/data-table';
import { PageHeader } from '@/components/common/page-header';
import { PagePagination } from '@/components/common/pagination';
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useDebounce } from '@/hooks/use-debounce';
import { useQueryParams } from '@/hooks/use-query-params';
import { normalizeError } from '@/services/error';
import { ADMIN_PAGE_SIZE } from '@/shared/config';
import { formatDateTime } from '@/utils/format';

import { useAdminUsers, useUpdateUser, type AdminUser } from '../users-api';

const DEFAULTS = {
  page: 0,
  size: ADMIN_PAGE_SIZE,
  search: '',
  role: '',
  status: '',
  sortBy: 'createdAt',
  direction: 'desc' as 'asc' | 'desc',
};

const ALL = '__all__';

/**
 * Every account on the platform.
 *
 * Two things this screen deliberately cannot do. It cannot create an account — people register
 * themselves — and it cannot set anybody's password. An operator helping someone back in sends
 * them a reset link, which keeps the property that nobody but the account holder has ever known
 * their own credential.
 *
 * The refusals it does surface come from the server and are worth reading rather than
 * paraphrasing: locking yourself out, and removing the last administrator, both leave a platform
 * that only a database console can recover.
 */
export default function AdminUsersPage() {
  const { params, setParams } = useQueryParams(DEFAULTS);
  const [editing, setEditing] = useState<AdminUser | null>(null);

  // The debounced value *is* the query key, so a half-typed search never becomes a request.
  const search = useDebounce(params.search, 350);

  const query = useAdminUsers({
    page: params.page,
    size: params.size,
    search: search || undefined,
    role: params.role || undefined,
    enabled: params.status === '' ? undefined : params.status === 'enabled',
    sortBy: params.sortBy,
    direction: params.direction,
  });

  const columns: Column<AdminUser>[] = [
    {
      id: 'account',
      header: 'Account',
      cell: (user) => (
        <div className="min-w-0">
          <p className="truncate font-medium">{user.fullName}</p>
          <p className="truncate font-mono text-xs text-muted-foreground">{user.email}</p>
        </div>
      ),
    },
    {
      id: 'roles',
      header: 'Roles',
      cell: (user) => (
        <div className="flex flex-wrap gap-1">
          {user.roles.map((role) => (
            <Badge key={role} variant={role === 'ADMIN' ? 'default' : 'secondary'}>
              {role === 'ADMIN' ? (
                <ShieldCheck className="mr-1 h-3 w-3" aria-hidden />
              ) : (
                <UserIcon className="mr-1 h-3 w-3" aria-hidden />
              )}
              {role}
            </Badge>
          ))}
        </div>
      ),
    },
    {
      id: 'status',
      header: 'Status',
      cell: (user) =>
        user.enabled ? (
          <Badge variant="secondary">Active</Badge>
        ) : (
          // Not "deleted": the account still exists, still owns its orders, and can be turned
          // back on. Calling it deleted would misdescribe what the button did.
          <Badge variant="destructive">Disabled</Badge>
        ),
    },
    {
      id: 'joined',
      header: 'Joined',
      hideOnMobile: true,
      cell: (user) => (
        <span className="text-sm text-muted-foreground">{formatDateTime(user.createdAt)}</span>
      ),
    },
    {
      id: 'actions',
      header: '',
      className: 'text-right',
      cell: (user) => (
        <Button variant="outline" size="sm" onClick={() => setEditing(user)}>
          Manage
        </Button>
      ),
    },
  ];

  const page = query.data;

  return (
    <div>
      <PageHeader
        title="Users"
        description="Every account, and what each one is allowed to do."
      />

      <div className="mb-4 flex flex-col gap-3 sm:flex-row">
        <Input
          placeholder="Search by name or email…"
          value={params.search}
          onChange={(event) => setParams({ search: event.target.value, page: 0 })}
          className="sm:max-w-xs"
          aria-label="Search accounts"
        />

        <Select
          value={params.role || ALL}
          onValueChange={(value) => setParams({ role: value === ALL ? '' : value, page: 0 })}
        >
          <SelectTrigger className="sm:w-44" aria-label="Filter by role">
            <SelectValue placeholder="All roles" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>All roles</SelectItem>
            <SelectItem value="CUSTOMER">Customer</SelectItem>
            <SelectItem value="ADMIN">Administrator</SelectItem>
          </SelectContent>
        </Select>

        <Select
          value={params.status || ALL}
          onValueChange={(value) => setParams({ status: value === ALL ? '' : value, page: 0 })}
        >
          <SelectTrigger className="sm:w-44" aria-label="Filter by status">
            <SelectValue placeholder="All statuses" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>All statuses</SelectItem>
            <SelectItem value="enabled">Active</SelectItem>
            <SelectItem value="disabled">Disabled</SelectItem>
          </SelectContent>
        </Select>
      </div>

      <DataTable
        columns={columns}
        rows={page?.content}
        getRowId={(user) => user.id}
        isLoading={query.isLoading}
        error={query.isError ? normalizeError(query.error) : null}
        onRetry={() => void query.refetch()}
        emptyTitle="No accounts match"
        emptyDescription="Try a broader search, or clear the filters."
        skeletonRows={params.size}
      />

      <PagePagination data={page} onPageChange={(next) => setParams({ page: next })} />

      <ManageUserDialog user={editing} onClose={() => setEditing(null)} />
    </div>
  );
}

function ManageUserDialog({ user, onClose }: { user: AdminUser | null; onClose: () => void }) {
  const updateUser = useUpdateUser();
  const [roles, setRoles] = useState<string[]>([]);

  // Reset whenever a different account is opened, so yesterday's selection never leaks into
  // today's edit.
  const [openedFor, setOpenedFor] = useState<string | null>(null);
  if (user && openedFor !== user.id) {
    setOpenedFor(user.id);
    setRoles(user.roles);
  }

  if (!user) {
    return null;
  }

  const isAdmin = roles.includes('ADMIN');

  const save = async (changes: { enabled?: boolean; roles?: string[] }) => {
    await updateUser.mutateAsync({ id: user.id, payload: changes });
    onClose();
  };

  return (
    <Dialog open={Boolean(user)} onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{user.fullName}</DialogTitle>
        </DialogHeader>

        <p className="font-mono text-sm text-muted-foreground">{user.email}</p>

        <div className="mt-4 space-y-4">
          <div className="space-y-2">
            <Label htmlFor="role-toggle">Administrator</Label>
            <div className="flex items-center gap-3">
              <input
                id="role-toggle"
                type="checkbox"
                checked={isAdmin}
                onChange={(event) =>
                  setRoles(
                    event.target.checked
                      ? Array.from(new Set([...roles, 'ADMIN']))
                      : roles.filter((role) => role !== 'ADMIN'),
                  )
                }
                className="h-4 w-4"
              />
              <span className="text-sm text-muted-foreground">
                Full access to this console, including other accounts.
              </span>
            </div>
          </div>

          <div className="rounded-md border bg-muted/40 p-3 text-sm text-muted-foreground">
            {user.enabled
              ? 'Disabling signs this account out everywhere immediately — not whenever its session happens to expire.'
              : 'This account cannot sign in. Re-enabling does not restore its old sessions; the person signs in again.'}
          </div>
        </div>

        <DialogFooter className="gap-2 sm:justify-between">
          <Button
            variant={user.enabled ? 'destructive' : 'outline'}
            onClick={() => void save({ enabled: !user.enabled })}
            loading={updateUser.isPending}
          >
            {user.enabled ? 'Disable account' : 'Re-enable account'}
          </Button>

          <div className="flex gap-2">
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button
              onClick={() => void save({ roles })}
              loading={updateUser.isPending}
              disabled={roles.length === 0}
            >
              Save roles
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

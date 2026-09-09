import { useQuery } from '@tanstack/react-query';
import { LogOut, MapPin, Package, ShieldCheck } from 'lucide-react';
import { Link } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { ErrorState } from '@/components/common/states';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { authApi } from '@/features/auth/api';
import { useLogout } from '@/features/auth/hooks';
import { useAuthStore } from '@/features/auth/store';
import { useAddresses } from '@/features/user/addresses-api';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { staleTimes } from '@/shared/config';
import { formatDate, initials } from '@/utils/format';

/**
 * Account profile.
 *
 * Read-only, because Auth Service has no update endpoint — `/api/auth/me` is a GET and there is
 * no `PUT /api/auth/me` or equivalent. Rather than render disabled inputs that look editable,
 * the profile shows the account as it is and says plainly what cannot be changed here yet.
 */
export default function ProfilePage() {
  const storedUser = useAuthStore((state) => state.user);
  const logout = useLogout();
  const addressCount = useAddresses().data?.length ?? 0;

  // Refetched rather than read from the store alone: roles and account status can change
  // server-side, and this is the screen where showing stale ones would matter most.
  const query = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: () => authApi.me(),
    staleTime: staleTimes.session,
    initialData: storedUser ?? undefined,
  });

  const user = query.data;

  if (query.isError && !user) {
    return (
      <ErrorState error={normalizeError(query.error)} onRetry={() => void query.refetch()} />
    );
  }

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        title="Profile"
        breadcrumbs={[{ label: 'Home', to: paths.home }, { label: 'Profile' }]}
      />

      <div className="space-y-6">
        <Card>
          <CardContent className="flex flex-col gap-4 p-6 sm:flex-row sm:items-center">
            <Avatar className="h-16 w-16">
              <AvatarFallback className="text-lg">{initials(user?.fullName)}</AvatarFallback>
            </Avatar>

            <div className="min-w-0 flex-1">
              {user ? (
                <>
                  <p className="truncate text-lg font-semibold">{user.fullName}</p>
                  <p className="truncate text-sm text-muted-foreground">{user.email}</p>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {user.roles.map((role) => (
                      <Badge key={role} variant={role === 'ADMIN' ? 'default' : 'secondary'}>
                        {role === 'ADMIN' ? (
                          <>
                            <ShieldCheck className="mr-1 h-3 w-3" aria-hidden />
                            Administrator
                          </>
                        ) : (
                          'Customer'
                        )}
                      </Badge>
                    ))}
                  </div>
                </>
              ) : (
                <div className="space-y-2">
                  <Skeleton className="h-5 w-40" />
                  <Skeleton className="h-4 w-56" />
                </div>
              )}
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Account details</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <DetailRow label="Full name" value={user?.fullName} />
            <DetailRow label="Email" value={user?.email} />
            <DetailRow label="Phone" value={user?.phone ?? 'Not provided'} />
            <DetailRow label="Member since" value={user ? formatDate(user.createdAt) : undefined} />
            <DetailRow label="Status" value={user?.enabled ? 'Active' : 'Disabled'} />

            <Separator />

            {/* Honest about the gap rather than showing a disabled "Edit" button. */}
            <p className="text-xs text-muted-foreground">
              Editing these details is not available yet — Auth Service exposes{' '}
              <code className="font-mono">GET /api/auth/me</code> but no update endpoint. It needs
              a <code className="font-mono">PUT /api/auth/me</code> before this screen can offer
              a form.
            </p>
          </CardContent>
        </Card>

        <div className="grid gap-4 sm:grid-cols-2">
          <Link to={paths.orders}>
            <Card className="h-full transition-colors hover:border-primary hover:bg-accent/30">
              <CardContent className="flex items-center gap-3 p-5">
                <Package className="h-5 w-5 text-muted-foreground" aria-hidden />
                <div>
                  <p className="text-sm font-medium">My orders</p>
                  <p className="text-xs text-muted-foreground">Track and review past orders</p>
                </div>
              </CardContent>
            </Card>
          </Link>

          <Link to={paths.addresses}>
            <Card className="h-full transition-colors hover:border-primary hover:bg-accent/30">
              <CardContent className="flex items-center gap-3 p-5">
                <MapPin className="h-5 w-5 text-muted-foreground" aria-hidden />
                <div>
                  <p className="text-sm font-medium">Addresses</p>
                  <p className="text-xs text-muted-foreground">
                    {addressCount} saved {addressCount === 1 ? 'address' : 'addresses'}
                  </p>
                </div>
              </CardContent>
            </Card>
          </Link>
        </div>

        <Button
          variant="outline"
          className="text-destructive hover:text-destructive"
          onClick={() => logout.mutate()}
          loading={logout.isPending}
        >
          <LogOut aria-hidden />
          Sign out
        </Button>
      </div>
    </div>
  );
}

function DetailRow({ label, value }: { label: string; value?: string }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-muted-foreground">{label}</span>
      {value ? <span className="text-right">{value}</span> : <Skeleton className="h-4 w-32" />}
    </div>
  );
}

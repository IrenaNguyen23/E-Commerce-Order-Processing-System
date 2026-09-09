import {
  Bell,
  Heart,
  LayoutDashboard,
  LogOut,
  Menu,
  Package,
  ShoppingCart,
  User as UserIcon,
  X,
} from 'lucide-react';
import { useState } from 'react';
import { Link, NavLink, useNavigate } from 'react-router-dom';

import { SearchInput } from '@/components/common/search-input';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useLogout } from '@/features/auth/hooks';
import { useAuthStore } from '@/features/auth/store';
import { selectCartCount, useCartStore } from '@/features/cart/store';
import { selectWishlistCount, useWishlistStore } from '@/features/wishlist/store';
import { paths } from '@/routes/paths';
import { config } from '@/shared/config';
import { cn } from '@/utils/cn';
import { initials } from '@/utils/format';

const NAV_LINKS = [
  { to: paths.products, label: 'Shop' },
  { to: paths.categories, label: 'Categories' },
];

export function SiteHeader() {
  const navigate = useNavigate();
  const [mobileOpen, setMobileOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');

  const user = useAuthStore((state) => state.user);
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const isAdmin = useAuthStore((state) => state.isAdmin());
  const logout = useLogout();

  // Subscribing to the derived count, not the item array, so adding a quantity does not
  // re-render the whole header tree.
  const cartCount = useCartStore(selectCartCount);
  const wishlistCount = useWishlistStore(selectWishlistCount);

  const submitSearch = (term: string) => {
    setSearchTerm(term);
    if (term.trim()) {
      navigate(`${paths.search}?q=${encodeURIComponent(term.trim())}`);
    }
  };

  return (
    <header className="sticky top-0 z-40 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/80">
      <div className="container flex h-16 items-center gap-4">
        <Button
          variant="ghost"
          size="icon"
          className="md:hidden"
          onClick={() => setMobileOpen((open) => !open)}
          aria-label={mobileOpen ? 'Close menu' : 'Open menu'}
          aria-expanded={mobileOpen}
        >
          {mobileOpen ? <X aria-hidden /> : <Menu aria-hidden />}
        </Button>

        <Link to={paths.home} className="flex shrink-0 items-center gap-2 font-semibold">
          <Package className="h-5 w-5 text-primary" aria-hidden />
          <span className="hidden sm:inline">{config.appName}</span>
        </Link>

        <nav className="hidden items-center gap-1 md:flex" aria-label="Main">
          {NAV_LINKS.map((link) => (
            <NavLink
              key={link.to}
              to={link.to}
              className={({ isActive }) =>
                cn(
                  'rounded-md px-3 py-2 text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-accent text-accent-foreground'
                    : 'text-muted-foreground hover:text-foreground',
                )
              }
            >
              {link.label}
            </NavLink>
          ))}
        </nav>

        <div className="ml-auto hidden max-w-sm flex-1 lg:block">
          <SearchInput
            value={searchTerm}
            onChange={submitSearch}
            placeholder="Search the catalogue…"
          />
        </div>

        <div className="ml-auto flex items-center gap-1 lg:ml-0">
          <Button variant="ghost" size="icon" asChild className="relative">
            <Link to={paths.wishlist} aria-label={`Wishlist, ${wishlistCount} items`}>
              <Heart aria-hidden />
              {wishlistCount > 0 ? <CountBadge value={wishlistCount} /> : null}
            </Link>
          </Button>

          <Button variant="ghost" size="icon" asChild className="relative">
            <Link to={paths.cart} aria-label={`Basket, ${cartCount} items`}>
              <ShoppingCart aria-hidden />
              {cartCount > 0 ? <CountBadge value={cartCount} /> : null}
            </Link>
          </Button>

          {isAuthenticated ? (
            <>
              <Button variant="ghost" size="icon" asChild className="hidden sm:inline-flex">
                <Link to={paths.notifications} aria-label="Notifications">
                  <Bell aria-hidden />
                </Link>
              </Button>

              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="icon" aria-label="Account menu">
                    <Avatar>
                      <AvatarFallback>{initials(user?.fullName)}</AvatarFallback>
                    </Avatar>
                  </Button>
                </DropdownMenuTrigger>

                <DropdownMenuContent align="end" className="w-56">
                  <DropdownMenuLabel className="font-normal">
                    <p className="truncate text-sm font-medium">{user?.fullName}</p>
                    <p className="truncate text-xs text-muted-foreground">{user?.email}</p>
                  </DropdownMenuLabel>
                  <DropdownMenuSeparator />

                  <DropdownMenuItem asChild>
                    <Link to={paths.orders}>
                      <Package aria-hidden />
                      My orders
                    </Link>
                  </DropdownMenuItem>
                  <DropdownMenuItem asChild>
                    <Link to={paths.profile}>
                      <UserIcon aria-hidden />
                      Profile
                    </Link>
                  </DropdownMenuItem>
                  <DropdownMenuItem asChild>
                    <Link to={paths.notifications}>
                      <Bell aria-hidden />
                      Notifications
                    </Link>
                  </DropdownMenuItem>

                  {isAdmin ? (
                    <>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem asChild>
                        <Link to={paths.admin.dashboard}>
                          <LayoutDashboard aria-hidden />
                          Admin console
                        </Link>
                      </DropdownMenuItem>
                    </>
                  ) : null}

                  <DropdownMenuSeparator />
                  <DropdownMenuItem
                    onSelect={() => logout.mutate()}
                    className="text-destructive focus:text-destructive"
                  >
                    <LogOut aria-hidden />
                    Sign out
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </>
          ) : (
            <div className="flex items-center gap-2 pl-1">
              <Button variant="ghost" size="sm" asChild className="hidden sm:inline-flex">
                <Link to={paths.login}>Sign in</Link>
              </Button>
              <Button size="sm" asChild>
                <Link to={paths.register}>Register</Link>
              </Button>
            </div>
          )}
        </div>
      </div>

      {/* Mobile: search plus the nav links the desktop bar shows inline. */}
      {mobileOpen ? (
        <div className="border-t md:hidden">
          <div className="container space-y-3 py-3">
            <SearchInput value={searchTerm} onChange={submitSearch} placeholder="Search…" />
            <nav className="flex flex-col" aria-label="Mobile">
              {NAV_LINKS.map((link) => (
                <NavLink
                  key={link.to}
                  to={link.to}
                  onClick={() => setMobileOpen(false)}
                  className={({ isActive }) =>
                    cn(
                      'rounded-md px-3 py-2 text-sm font-medium',
                      isActive ? 'bg-accent' : 'text-muted-foreground',
                    )
                  }
                >
                  {link.label}
                </NavLink>
              ))}
            </nav>
          </div>
        </div>
      ) : null}
    </header>
  );
}

function CountBadge({ value }: { value: number }) {
  return (
    <Badge
      variant="destructive"
      className="absolute -right-1 -top-1 h-5 min-w-5 justify-center px-1 text-[10px] tabular"
    >
      {value > 99 ? '99+' : value}
    </Badge>
  );
}

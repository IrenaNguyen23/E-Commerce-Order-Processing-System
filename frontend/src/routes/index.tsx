import { lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';

import { AdminLayout } from '@/layouts/admin-layout';
import { AuthLayout } from '@/layouts/auth-layout';
import { CustomerLayout } from '@/layouts/customer-layout';

import { RedirectIfAuthenticated, RequireAdmin, RequireAuth } from './guards';
import { paths } from './paths';

/**
 * Every page is lazy.
 *
 * This is what keeps the admin console — its tables, dialogs and forms — out of the bundle a
 * shopper downloads. Each layout renders its own `<Suspense>`, so a route transition shows a
 * loader inside the shell rather than blanking the whole page.
 */

// ---- customer -----------------------------------------------------------------------------
const HomePage = lazy(() => import('@/features/product/pages/home-page'));
const ProductListPage = lazy(() => import('@/features/product/pages/product-list-page'));
const ProductDetailPage = lazy(() => import('@/features/product/pages/product-detail-page'));
const SearchPage = lazy(() => import('@/features/product/pages/search-page'));
const CategoryIndexPage = lazy(() =>
  import('@/features/product/pages/category-pages').then((module) => ({
    default: module.CategoryIndexPage,
  })),
);
const CategoryPage = lazy(() =>
  import('@/features/product/pages/category-pages').then((module) => ({
    default: module.CategoryPage,
  })),
);
const CartPage = lazy(() => import('@/features/cart/pages/cart-page'));
const WishlistPage = lazy(() => import('@/features/wishlist/pages/wishlist-page'));

// ---- customer, authenticated ----------------------------------------------------------------
const CheckoutPage = lazy(() => import('@/features/order/pages/checkout-page'));
const OrderSuccessPage = lazy(() => import('@/features/order/pages/order-success-page'));
const MyOrdersPage = lazy(() => import('@/features/order/pages/my-orders-page'));
const OrderDetailPage = lazy(() => import('@/features/order/pages/order-detail-page'));
const NotificationsPage = lazy(() => import('@/features/notification/pages/notifications-page'));
const ProfilePage = lazy(() => import('@/features/user/pages/profile-page'));
const AddressesPage = lazy(() => import('@/features/user/pages/addresses-page'));

// ---- auth ------------------------------------------------------------------------------------
const LoginPage = lazy(() => import('@/features/auth/pages/login-page'));
const RegisterPage = lazy(() => import('@/features/auth/pages/register-page'));
const ForgotPasswordPage = lazy(() => import('@/features/auth/pages/forgot-password-page'));
const ResetPasswordPage = lazy(() => import('@/features/auth/pages/reset-password-page'));
const VerifyEmailPage = lazy(() => import('@/features/auth/pages/verify-email-page'));

// ---- legal -----------------------------------------------------------------------------------
const TermsPage = lazy(() => import('@/features/legal/pages/terms-page'));
const PrivacyPage = lazy(() => import('@/features/legal/pages/privacy-page'));
const ReturnsPage = lazy(() => import('@/features/legal/pages/returns-page'));
const ContactPage = lazy(() => import('@/features/legal/pages/contact-page'));

// ---- admin -----------------------------------------------------------------------------------
const DashboardPage = lazy(() => import('@/features/admin/pages/dashboard-page'));
const AdminProductsPage = lazy(() => import('@/features/admin/pages/admin-products-page'));
const AdminInventoryPage = lazy(() => import('@/features/admin/pages/admin-inventory-page'));
const AdminCategoriesPage = lazy(() => import('@/features/admin/pages/admin-categories-page'));
const AdminOrdersPage = lazy(() => import('@/features/admin/pages/admin-orders-page'));
const AdminOrderDetailPage = lazy(() => import('@/features/admin/pages/admin-order-detail-page'));
const AdminPaymentsPage = lazy(() => import('@/features/admin/pages/admin-payments-page'));
const AdminReportsPage = lazy(() => import('@/features/admin/pages/admin-reports-page'));
const AdminUsersPage = lazy(() => import('@/features/admin/pages/admin-users-page'));
const AdminCouponsPage = lazy(() => import('@/features/admin/pages/admin-coupons-page'));
const AdminWarehousesPage = lazy(
  () => import('@/features/admin/pages/admin-warehouses-page'),
);
const AdminReviewsPage = lazy(() => import('@/features/admin/pages/admin-reviews-page'));
const AdminShipmentsPage = lazy(
  () => import('@/features/admin/pages/admin-shipments-page'),
);
const AdminReturnsPage = lazy(() => import('@/features/admin/pages/admin-returns-page'));
const AdminAuditPage = lazy(() => import('@/features/admin/pages/admin-audit-page'));

const NotFoundPage = lazy(() => import('./not-found-page'));

export function AppRoutes() {
  return (
    <Routes>
      {/* ---- auth: its own shell, and unreachable once signed in ---------------------- */}
      <Route
        element={
          <RedirectIfAuthenticated>
            <AuthLayout />
          </RedirectIfAuthenticated>
        }
      >
        <Route path={paths.login} element={<LoginPage />} />
        <Route path={paths.register} element={<RegisterPage />} />
        <Route path={paths.forgotPassword} element={<ForgotPasswordPage />} />
        <Route path={paths.resetPassword} element={<ResetPasswordPage />} />
        <Route path={paths.verifyEmail} element={<VerifyEmailPage />} />
      </Route>

      {/* ---- storefront --------------------------------------------------------------- */}
      <Route element={<CustomerLayout />}>
        <Route path={paths.home} element={<HomePage />} />
        <Route path={paths.products} element={<ProductListPage />} />
        <Route path={paths.productPattern} element={<ProductDetailPage />} />
        <Route path={paths.search} element={<SearchPage />} />
        <Route path={paths.categories} element={<CategoryIndexPage />} />
        <Route path={paths.categoryPattern} element={<CategoryPage />} />

        {/* Public on purpose — these have to be readable before somebody decides to buy. */}
        <Route path={paths.terms} element={<TermsPage />} />
        <Route path={paths.privacy} element={<PrivacyPage />} />
        <Route path={paths.returns} element={<ReturnsPage />} />
        <Route path={paths.contact} element={<ContactPage />} />

        {/* Basket and wishlist stay open: they are local, and forcing a sign-in before a
            customer can even collect items is the fastest way to lose them. */}
        <Route path={paths.cart} element={<CartPage />} />
        <Route path={paths.wishlist} element={<WishlistPage />} />

        {/* Checkout needs an identity, not necessarily a chosen one. A guest gets a real
            account with no password, so this page is reachable without signing in and the
            page itself asks for an email first. Bouncing somebody with a full basket to a
            registration form is the most expensive redirect in a shop. */}
        <Route path={paths.checkout} element={<CheckoutPage />} />
        <Route
          path={paths.orderSuccessPattern}
          element={
            <RequireAuth>
              <OrderSuccessPage />
            </RequireAuth>
          }
        />
        <Route
          path={paths.orders}
          element={
            <RequireAuth>
              <MyOrdersPage />
            </RequireAuth>
          }
        />
        <Route
          path={paths.orderPattern}
          element={
            <RequireAuth>
              <OrderDetailPage />
            </RequireAuth>
          }
        />
        <Route
          path={paths.notifications}
          element={
            <RequireAuth>
              <NotificationsPage />
            </RequireAuth>
          }
        />
        <Route
          path={paths.profile}
          element={
            <RequireAuth>
              <ProfilePage />
            </RequireAuth>
          }
        />
        <Route
          path={paths.addresses}
          element={
            <RequireAuth>
              <AddressesPage />
            </RequireAuth>
          }
        />

        <Route path="/account" element={<Navigate to={paths.profile} replace />} />
        <Route path={paths.notFound} element={<NotFoundPage />} />
      </Route>

      {/* ---- admin console ------------------------------------------------------------ */}
      <Route
        path={paths.admin.root}
        element={
          <RequireAdmin>
            <AdminLayout />
          </RequireAdmin>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="products" element={<AdminProductsPage />} />
        <Route path="inventory" element={<AdminInventoryPage />} />
        <Route path="categories" element={<AdminCategoriesPage />} />
        <Route path="orders" element={<AdminOrdersPage />} />
        <Route path="orders/:orderId" element={<AdminOrderDetailPage />} />
        <Route path="payments" element={<AdminPaymentsPage />} />
        <Route path="reports" element={<AdminReportsPage />} />

        <Route path="users" element={<AdminUsersPage />} />
        <Route path="coupons" element={<AdminCouponsPage />} />
        <Route path="reviews" element={<AdminReviewsPage />} />
        <Route path="warehouses" element={<AdminWarehousesPage />} />
        <Route path="shipments" element={<AdminShipmentsPage />} />
        <Route path="returns" element={<AdminReturnsPage />} />
        <Route path="audit" element={<AdminAuditPage />} />

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

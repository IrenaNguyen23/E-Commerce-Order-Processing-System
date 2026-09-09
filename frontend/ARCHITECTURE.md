# CommerceFlow Web — Architecture

Frontend for the CommerceFlow platform. React 18 + Vite + TypeScript, feature-based, built
against the API the backend in this repository actually exposes.

---

## 1. Backend analysis

Every endpoint below was read from the controllers in `services/*/controller/` and cross-checked
against `docs/api/commerceflow-openapi.yaml`. **This is the complete API surface — 20 operations
across 5 services.** Everything reaches the browser through the gateway on `:8080`.

### Auth Service — `/api/auth`

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/register` | — | 409 `EMAIL_ALREADY_REGISTERED` |
| POST | `/login` | — | Returns `{accessToken, refreshToken, tokenType, expiresIn, user}` |
| POST | `/refresh` | — | **Single use** — rotates the pair; reuse revokes every session |
| POST | `/logout` | Bearer | Denies the access token + revokes all refresh tokens |
| GET | `/me` | Bearer | Current account |

### Inventory Service — `/api/products`

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `` | — | `category`, `search`, `activeOnly`, `page`, `size`, `sortBy`, `direction` |
| GET | `/{id}` | — | |
| GET | `/sku/{sku}` | — | |
| POST | `/lookup` | — | Batch by ids, max 100 |
| POST | `` | ADMIN | Create product + opening stock |
| PUT | `/{id}/stock` | ADMIN | `SET` \| `INCREASE` \| `DECREASE` |

### Order Service — `/api/orders`

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `` | Bearer | Returns `201` with status `CREATED`; the saga then runs **asynchronously** |
| GET | `` | Bearer | Customer sees own; ADMIN sees all + `userId` filter |
| GET | `/{id}` | Bearer | Unknown and foreign both return 404 |
| GET | `/number/{orderNumber}` | Bearer | |

### Payment Service — `/api/payments`

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/process` | Bearer | Manual/retry path; `402` on decline **with the payment as body** |
| GET | `/{id}` | Bearer | |
| GET | `/order/{orderId}` | Bearer | |

### Notification Service — `/api/notifications`

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `` | Bearer | Own; ADMIN sees all |
| GET | `/{id}` | Bearer | |

### Response envelopes

Uniform across every endpoint — the API layer unwraps them once, so no feature ever sees them.

```jsonc
// success
{ "success": true, "data": <T>, "message": "…", "timestamp": "…", "correlationId": "…" }

// error
{ "success": false, "status": 409, "error": "Conflict", "code": "INSUFFICIENT_STOCK",
  "message": "…", "path": "/api/orders", "correlationId": "…",
  "fieldErrors": [{ "field": "email", "rejectedValue": "…", "message": "…" }] }

// paged data
{ "content": [...], "page": 0, "size": 20, "totalElements": 137,
  "totalPages": 7, "first": true, "last": false }
```

`code` is a stable machine-readable enum (28 values). The UI maps it to a message and, for
`fieldErrors`, straight onto React Hook Form fields.

---

## 2. Scope: requested vs. available

The brief lists twelve services. **Five exist.** Rather than mock the rest and ship screens that
lie, each requested feature is placed in one of three buckets.

### ✅ Built on real endpoints

Authentication · Product listing/detail/search · Category browsing · Checkout · Order tracking ·
Payment status · Notifications · Admin product, inventory, order and payment management.

### ✅ Built client-side — and this is the correct design, not a workaround

| Feature | Why client-side is right |
|---|---|
| **Cart** | `POST /api/orders` takes the entire item list in one request. A server cart would add a round trip, a second source of truth and a sync problem for zero benefit. Zustand + `localStorage`, prices re-validated against the catalogue before checkout. |
| **Wishlist** | Same reasoning; pure user preference, no server state to reconcile. |
| **Address book** | The order carries `shippingAddress` as a **single string**. A local book that composes that string gives the full UX with no backend change. |
| **Categories** | `category` is a plain string column on the product, not an entity. Categories are derived from the catalogue and the "category page" is a filtered listing — which is exactly what `GET /api/products?category=` serves. |

These are fully functional. They are marked in the code with the reason, so nobody later mistakes
them for something that *should* have been server-side.

### ⚠️ Cannot work — no backend endpoint

Forgot/Reset password · Admin user management · Reviews · Coupons/Promotions · Shipping tracking ·
Server-side analytics.

These get a real, designed screen that states plainly which endpoint is missing and specifies what
the backend needs to add. **They never fake success.** `ServiceUnavailable` renders that
specification, so the screen doubles as a backlog item.

Two are partially recoverable and are built for real:
- **Admin dashboard / reports** — derived client-side from `GET /api/orders` (ADMIN sees all, with
  status filter and paging). Genuinely useful, honestly labelled as derived.
- **Payment management** — no list endpoint exists, so admin looks a payment up by order id.

---

## 3. Tech stack

| Concern | Choice | Why this one |
|---|---|---|
| Build | **Vite 5** | Fast HMR, native ESM, first-class TS |
| Language | **TypeScript (strict)** | Every DTO typed from the OpenAPI contract |
| Routing | **React Router 6** | Nested layouts, lazy routes, loader-free (Query owns data) |
| Server state | **TanStack Query 5** | Caching, dedupe, background refetch — and **polling**, which this backend needs: orders complete asynchronously |
| Client state | **Zustand** | Cart, wishlist, auth session, addresses. Tiny, no boilerplate, `persist` built in |
| HTTP | **Axios** | Interceptors are the cleanest place for refresh-token queueing |
| Forms | **React Hook Form + Zod** | Uncontrolled = fast; Zod schema is also the TS type |
| Styling | **Tailwind CSS** | No naming, no dead CSS, trivial responsive |
| Components | **shadcn//ui pattern (Radix + CVA)** | Owned source, not a dependency — accessible primitives, restyleable |
| Toasts | **sonner** | One line, accessible, no provider tree |
| Icons | **lucide-react** | Tree-shakeable |
| Lists | *(none)* | Virtualization was considered and left out — admin tables page at 20 rows, so it would be complexity without a measurable win |

**Deliberately excluded:** Redux (Query + Zustand cover it), a component library like MUI (fights
Tailwind, ships weight), SSR/Next (this is an authenticated SPA behind a gateway; SSR buys nothing
and costs a Node tier).

---

## 4. Architecture

### Layering — strict, one direction

```
       pages  ─────────────┐   route-level composition, no data logic
          │                │
     components  ──────────┤   presentational; props in, events out
          │                │
        hooks  ────────────┤   TanStack Query wrappers — the only place queries live
          │                │
         api   ────────────┤   per-feature client; returns domain types, never envelopes
          │                │
    services/api-client ───┘   axios instance: auth, refresh, retry, error normalisation
```

A page never imports axios. A component never imports an api module. This is what keeps features
independently testable and replaceable.

### The three cross-cutting decisions

**1. Envelope unwrapping happens once.** A response interceptor returns `data.data`, so
`getProduct()` resolves to `Product`, not `ApiResponse<Product>`. Every layer above is clean.

**2. Refresh-token queueing.** Refresh tokens are single use and reusing one revokes every session
— so two concurrent 401s must not both call `/refresh`. The client keeps a single in-flight
refresh promise and queues every other failed request behind it. This is the highest-risk piece of
the whole app and it is isolated in one file.

**3. The saga is asynchronous, so the UI polls.** `POST /api/orders` returns `CREATED`;
inventory, payment and notification resolve over Kafka in the following seconds. `useOrderPolling`
refetches until the order reaches `COMPLETED` or `CANCELLED`, then stops. The order-success screen
shows live saga progress instead of a spinner and a lie.

### Error handling

`normalizeError` turns anything — axios network failure, backend `ErrorResponse`, timeout — into
one `AppError { code, message, status, fieldErrors, correlationId }`. From there:
- `fieldErrors` → `setError()` on the matching RHF field;
- everything else → toast, with the `correlationId` shown so a user can quote it in support;
- `401` → refresh, then retry, then log out;
- `5xx`/network → retried with backoff (idempotent methods only; **never** `POST /api/orders`).

---

## 5. Project structure

```
src/
├── app/              providers, query client, root
├── routes/           route tree, guards, path constants
├── layouts/          Customer · Admin · Auth shells
├── features/
│   ├── auth/         api · schemas · hooks · store · components · pages
│   ├── product/
│   ├── cart/         local (Zustand + persist)
│   ├── order/
│   ├── payment/
│   ├── notification/
│   ├── user/         profile + local address book
│   ├── wishlist/     local
│   └── admin/
├── services/         api-client, token storage, error normalisation, endpoints
├── components/
│   ├── ui/           shadcn primitives (owned source)
│   └── common/       DataTable, Pagination, EmptyState, ErrorState, Skeletons…
├── hooks/            useDebounce, useQueryParams, useMediaQuery
├── types/            API envelopes, shared domain types
├── utils/            cn, format, storage, constants
└── shared/           config, cross-feature constants
```

Each feature folder is self-contained: delete it and nothing outside breaks except its routes.

---

## 6. Performance

| Technique | Where |
|---|---|
| Route-level code splitting | Every page is `React.lazy` — the admin bundle never reaches a customer |
| Manual vendor chunks | react, router, query, radix split so a feature change does not bust vendor cache |
| Query caching | 5 min `staleTime` on the catalogue; orders poll only while a saga is in flight |
| `React.memo` | `ProductCard` and table rows only — where a list re-renders on parent state |
| Server-side paging | Every list is paged by the backend, so no client list is ever long enough to need virtualization |
| Debounced search | 350 ms, and the query key is the debounced value so no wasted request |
| Image `loading="lazy"` | Product grids |
| `select` in queries | Derived values computed in the cache, not on every render |

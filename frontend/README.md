# CommerceFlow Web

Storefront and admin console for the CommerceFlow platform. React 18 · Vite · TypeScript ·
Tailwind · TanStack Query · Zustand.

Built against the API the backend in this repository **actually exposes** — the 20 customer and
admin endpoints across 5 services. (There is a 21st, `POST /api/reservations/{orderId}/release`,
which is an operator lever with no UI: releasing stuck stock needs a decision the console cannot
make for you. Runbook section 1b.) [`ARCHITECTURE.md`](ARCHITECTURE.md) has the full analysis, the layering, and the
reasoning behind every scope decision. Read that before changing anything structural.

---

## Run it

### With the whole platform (recommended)

The frontend is a service in the root `docker-compose.yml`:

```bash
# from the repository root
python scripts/env.py init      # first checkout only; preserves an existing .env
docker compose up -d --build
```

| | |
|---|---|
| **Storefront** | http://localhost:3001 |
| API Gateway | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Kafka UI | http://localhost:8090 |
| Grafana | http://localhost:3000 (credentials in `.env`) |

Then give it something to show:

```bash
./scripts/seed-demo-data.sh
```

The catalogue seeds itself on start-up; orders do not, because they have to go through the saga
to exist consistently. The script places real ones and waits for them.

| Account | Password | |
|---|---|---|
| `ada@commerceflow.io` | `DEMO_PASSWORD` in `.env` | 5 orders: three completed, one declined, one out of stock |
| `liam@commerceflow.io` | `DEMO_PASSWORD` in `.env` | 3 orders, one of them declined |
| `COMMERCEFLOW_BOOTSTRAP_ADMIN_EMAIL` in `.env` | `COMMERCEFLOW_BOOTSTRAP_ADMIN_PASSWORD` in `.env` | The admin console |

Without the script the storefront works fine, but `/orders`, `/notifications` and every admin
screen start empty — there is nothing to page, filter or chart yet.

### Frontend only, against a running backend

Requires Node 20+.

```bash
cd frontend
# Vite reads ../.env, shared with Docker Compose
npm install
npm run dev               # http://localhost:5173
```

Vite proxies `/api` to `http://localhost:8080`, so the browser stays same-origin and there is no
CORS to configure. Point it elsewhere with `VITE_API_PROXY_TARGET`.

### Scripts

| Command | Does |
|---|---|
| `npm run dev` | Dev server with HMR |
| `npm run build` | Type-check (`tsc -b`) then bundle — a type error fails the build |
| `npm run preview` | Serve the production bundle locally |
| `npm run typecheck` | Types only |
| `npm run lint` | ESLint, zero warnings tolerated |
| `npm run format` | Prettier |

---

## Try the whole flow

The seeded catalogue makes all three saga outcomes reachable without any setup.

1. **Register** at `/register`, or sign in as Ada above.
2. **Add a laptop** (€1,899) to the basket and check out → the order completes.
   Watch the timeline on the success screen fill in live as stock is reserved, payment is taken
   and the order is confirmed.
3. **Order six laptops** (€11,394) → the simulated acquirer declines anything at or above
   €10,000. The order is cancelled, the reserved stock is released, and the screen tells you it
   stopped at the payment step.
4. **Order 50 of `CF-LIMITED-001`** (seeded with 2 units) → cancelled at the inventory step.
5. Check `/notifications` to see what the platform sent you for each.

Worth a look in the admin console, all of it seeded to be interesting rather than uniform:

| Screen | What the demo data puts there |
|---|---|
| `/admin` | Status counts across both customers, no longer all zero |
| `/admin/orders` | Completed and cancelled side by side, filterable |
| `/admin/payments` | Successful charges next to declines |
| `/admin/inventory` | `CF-SPEAKER-001` sold out, `CF-BAND-001` below its reorder level |
| `/admin/products` | `CF-PROTO-001` is deactivated — listed here, absent from the storefront |
| `/admin/reports` | Revenue, plus the two failure reasons grouped |

The catalogue holds nineteen products; eighteen are active, so the storefront is two pages at
twelve per page. Six categories and prices from €29 to €2,499 mean pagination, category
filtering, search and sorting all have something to act on.

---

## Build status

Verified on Node 24.19 / npm 11.17:

```
tsc --noEmit      0 errors
eslint            0 errors, 0 warnings
vite build        ✓ 70 chunks, 910 kB total
```

Initial payload for a first-time shopper is **~84 kB gzipped** (`index` + `vendor-router` +
`vendor-query`). The admin console is not in it — every page is `React.lazy`, so the dashboard,
tables and dialogs only download when an administrator navigates to them.

### Known advisories

`npm audit` reports four, none of which is exploitable in this application. Stated rather than
silently carried:

| Advisory | Assessment |
|---|---|
| **esbuild ≤0.24.2** — dev server accepts cross-origin requests | Dev-only. The production image is nginx serving static files; esbuild is not in it. Fixing needs Vite 8, a major upgrade. |
| **react-router** — open redirect via backslash in `<Link>` / `useNavigate` | **Mitigated in our code.** `safeRedirect()` in `routes/paths.ts` validates the `?redirect=` parameter before it is ever navigated to: same-origin rooted paths only, no protocol-relative values, no backslashes, no control characters. Trusting a URL from a query string was our bug regardless of the library version. |
| **react-router** — constructor injection in `deserializeErrors()` | SSR hydration only. This app does not server-render. |

Upgrading to `react-router-dom@7` would clear the advisories outright. It is a major version bump,
so it is flagged here rather than done silently.

---

## Structure

```
src/
├── app/          providers, query client, error boundary
├── routes/       route tree, guards, path constants
├── layouts/      Customer · Admin · Auth shells
├── features/     auth · product · cart · order · payment · notification · user · wishlist · admin
│   └── <feature>/  api.ts · types.ts · hooks.ts · store.ts · schemas.ts · components/ · pages/
├── services/     api-client · token-storage · error · endpoints
├── components/   ui/ (owned shadcn primitives) · common/ (DataTable, Pagination, states…)
├── hooks/        useDebounce · useQueryParams · useMediaQuery · useCopyToClipboard
├── types/        API envelopes and AppError
├── utils/        cn · format · storage · constants
└── shared/       runtime config
```

Layering is strict and one-directional: `pages → components → hooks → api → services/api-client`.
A page never imports axios; a component never imports an api module.

---

## Three things worth knowing before you change anything

**1. Envelopes are unwrapped once.** The response interceptor returns `data.data`, so
`productApi.getById()` resolves to `Product`, not `ApiResponse<Product>`. Do not re-implement
that per feature.

**2. Refresh is single-flight.** The backend treats a reused refresh token as a compromise and
revokes every session. If three requests 401 at once and each calls `/refresh`, the user is
signed out everywhere. `api-client.ts` keeps one in-flight refresh promise and queues everything
behind it. **This is the highest-risk code in the app** — change it carefully.

**3. `POST /api/orders` never auto-retries.** A timeout does not mean the request failed; it
means the answer never arrived. Replaying it could place a second order. The call passes
`skipRetry`, and the customer-facing retry is covered by the `idempotencyKey`.

---

## What is not real, and why

Five of the twelve services in the original brief exist. Nothing is mocked to hide that.

**Client-side by design** — cart, wishlist, address book, categories. These are fully functional.
`POST /api/orders` takes the whole basket in one request, so a server cart would add a round trip
and a second source of truth for no benefit; `shippingAddress` is a single string, so an address
book composes one; `category` is a string column, so categories are derived from the catalogue.
Each is commented in place with the reason.

**No backend at all** — password reset, admin user management, coupons, reviews. These render a
designed screen naming the exact endpoints the backend needs. They never fake success. Delete the
screen and point the route at a real page the day the endpoint lands.

**Derived, and labelled** — the admin dashboard and reports compute figures from
`GET /api/orders`, because Order Service has no aggregate endpoint. Status counts are exact
(`totalElements` per filtered query); revenue is a sample of recent orders, and the UI says so
next to the number rather than in a footnote.

---

## Performance

| Technique | Where |
|---|---|
| Route-level code splitting | Every page is `React.lazy` — the admin bundle never reaches a shopper |
| Manual vendor chunks | react, router, query and forms split by change cadence |
| Query caching | 5 min `staleTime` on the catalogue; orders poll only while their saga runs |
| `React.memo` | `ProductCard` and `Pagination` — the components a grid re-renders in bulk |
| Debounced search | 350 ms, and the debounced value *is* the query key, so no wasted request |
| URL as state | Filters live in the URL, so back-navigation hits a warm cache |
| Lazy images | Product grids |

---

## Deploy

```bash
docker build -f frontend/Dockerfile -t commerceflow/web:1.0.0 ./frontend
docker run -p 3001:8080 -e GATEWAY_URL=http://api-gateway:8080 commerceflow/web:1.0.0
```

Node builds; nginx serves. The runtime image has no Node, no `node_modules` and no source.

**`GATEWAY_URL` is runtime configuration**, substituted into the nginx template at container
start — so one image runs against compose, staging and production without a rebuild.
`VITE_API_BASE_URL` is *build*-time, because Vite inlines it into the bundle; leave it empty so
the browser calls a relative `/api` and nginx proxies it. Same origin everywhere means no CORS.

Two cache rules that matter: fingerprinted `/assets/*` are immutable for a year, and
`index.html` is never cached — it is the only file whose name does not change, and a stale copy
would pin users to an old build permanently.

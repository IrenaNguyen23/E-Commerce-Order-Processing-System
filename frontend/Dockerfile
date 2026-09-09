# syntax=docker/dockerfile:1.7
# =====================================================================================
# CommerceFlow Web
#
# Multi-stage: Node builds the bundle, nginx serves it. The runtime image carries no Node,
# no node_modules and no source — just static files and a web server.
#
#   docker build -f frontend/Dockerfile -t commerceflow/web:1.0.0 ./frontend
# =====================================================================================

# ------------------------------------------------------------------ stage 1: dependencies
FROM node:20-alpine AS deps

WORKDIR /app

# Manifests only, so this layer is reused on every build that does not change a dependency.
COPY package.json package-lock.json* ./

# `npm ci` when a lockfile is present — reproducible, and fails loudly if it has drifted from
# package.json. Falls back to `install` so a fresh checkout without a lockfile still builds.
RUN --mount=type=cache,target=/root/.npm \
    if [ -f package-lock.json ]; then npm ci; else npm install; fi

# ------------------------------------------------------------------ stage 2: build
FROM node:20-alpine AS build

WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# Baked in at build time: Vite inlines env vars into the bundle, so this is not runtime config.
# Empty means "call /api on the same origin", which is what the nginx proxy below serves.
ARG VITE_API_BASE_URL=""
# Build-time because Vite inlines it. Safe in the bundle: a publishable key can only
# create payment attempts, never read or move money.
ARG VITE_STRIPE_PUBLISHABLE_KEY=""
ARG VITE_APP_NAME="CommerceFlow"
ENV VITE_STRIPE_PUBLISHABLE_KEY=$VITE_STRIPE_PUBLISHABLE_KEY \n    VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_APP_NAME=$VITE_APP_NAME

# `tsc -b` runs first via the build script: a type error fails the image rather than shipping.
RUN npm run build

# ------------------------------------------------------------------ stage 3: runtime
FROM nginx:1.27-alpine AS runtime

LABEL org.opencontainers.image.title="commerceflow-web" \
      org.opencontainers.image.description="CommerceFlow storefront and admin console" \
      org.opencontainers.image.vendor="CommerceFlow"

RUN apk add --no-cache curl

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/templates/default.conf.template

# Substituted into the template at container start by the nginx entrypoint, so one image runs
# against compose, staging and production without a rebuild.
ENV GATEWAY_URL=http://api-gateway:8080 \
    NGINX_ENVSUBST_TEMPLATE_SUFFIX=.template

# Port 8080, not 80: the unprivileged nginx user cannot bind a privileged port, and this matches
# every other service in the platform.
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8080/healthz || exit 1

STOPSIGNAL SIGQUIT

CMD ["nginx", "-g", "daemon off;"]

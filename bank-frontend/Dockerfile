# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# 1. Build the static bundle
# ---------------------------------------------------------------------------
FROM node:24-alpine AS build

WORKDIR /build

# Lockfile first: dependencies only reinstall when they actually change.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Vite inlines this at build time — it cannot be changed once the bundle exists.
#
# Empty by default, which makes the app call /api/v1/... on its own origin; nginx
# forwards those to the backend, so the browser never makes a cross-origin
# request and CORS never comes into it. Override with an absolute URL only if the
# API is served from somewhere the browser has to reach directly.
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

# ---------------------------------------------------------------------------
# 2. Serve
# ---------------------------------------------------------------------------
FROM nginx:1.31-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=5 \
    CMD wget -q -O /dev/null http://localhost/ || exit 1

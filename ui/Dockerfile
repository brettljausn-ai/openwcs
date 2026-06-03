# Build the SPA, then serve it with nginx (which also proxies /api -> gateway).
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# openssl ships with nginx:alpine; install explicitly so the entrypoint's
# self-signed cert generation is guaranteed to work.
RUN apk add --no-cache openssl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# Custom entrypoint: ensure a TLS cert exists (mounted real cert or self-signed),
# then start nginx in the foreground.
COPY docker-entrypoint.sh /docker-entrypoint-tls.sh
RUN chmod +x /docker-entrypoint-tls.sh
EXPOSE 80 443
ENTRYPOINT ["/docker-entrypoint-tls.sh"]

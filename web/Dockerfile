# The admin web: the Vite build served by nginx, which proxies /api to the hub, so the page calls its own
# origin (VITE_API_URL is empty) and the hub needs no CORS for it.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . ./
# Where people sign in (ClinicHub) and the client id registered there.
ARG VITE_OIDC_AUTHORITY=http://localhost:5301/
ARG VITE_OIDC_CLIENT_ID=integrationhub-admin
ENV VITE_API_URL="" VITE_OIDC_AUTHORITY=$VITE_OIDC_AUTHORITY VITE_OIDC_CLIENT_ID=$VITE_OIDC_CLIENT_ID
RUN npx vite build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# nginx:alpine fills ${...} in templates from the environment at start.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
ENV HUB_API_URL=http://integrationhub:5700
EXPOSE 80

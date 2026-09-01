FROM node:24-alpine AS console-build

WORKDIR /workspace/agent-console
COPY agent-console/package.json agent-console/package-lock.json ./
RUN npm ci
COPY agent-console ./
RUN npm run build

FROM nginx:1.27-alpine

RUN apk add --no-cache apache2-utils
COPY deployment/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY deployment/nginx/30-create-basic-auth.sh /docker-entrypoint.d/30-create-basic-auth.sh
COPY --from=console-build /workspace/agent-console/dist /usr/share/nginx/html
RUN chmod 0755 /docker-entrypoint.d/30-create-basic-auth.sh

EXPOSE 80 443

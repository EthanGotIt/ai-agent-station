#!/bin/sh
set -eu

if [ -z "${NGINX_BASIC_AUTH_USERNAME:-}" ] || [ -z "${NGINX_BASIC_AUTH_PASSWORD:-}" ]; then
    echo "NGINX_BASIC_AUTH_USERNAME and NGINX_BASIC_AUTH_PASSWORD are required." >&2
    exit 1
fi

htpasswd -bc /etc/nginx/.htpasswd "$NGINX_BASIC_AUTH_USERNAME" "$NGINX_BASIC_AUTH_PASSWORD" >/dev/null
chmod 0640 /etc/nginx/.htpasswd

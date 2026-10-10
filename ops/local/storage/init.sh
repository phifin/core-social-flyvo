#!/bin/sh
set -eu
umask 077
# Exact origins only; disallow JSON/shell metacharacters and paths.
case "$FRONTEND_ORIGIN" in
  http://*|https://*) ;;
  *) echo 'Invalid FRONTEND_ORIGIN scheme' >&2; exit 1 ;;
esac
origin_host=${FRONTEND_ORIGIN#*://}
case "$origin_host" in
  ''|*[!a-zA-Z0-9.:-]*) echo 'Invalid FRONTEND_ORIGIN host' >&2; exit 1 ;;
esac
admin_secret=$(tr -d '\r\n' </run/secrets/storage-admin.b64)
api_secret=$(tr -d '\r\n' </run/secrets/storage-api.b64)
worker_secret=$(tr -d '\r\n' </run/secrets/storage-worker.b64)
mcli --config-dir /tmp/mcli alias set local "${STORAGE_ENDPOINT:-http://silo:9000}" social-admin "$admin_secret" >/dev/null
for bucket in social-quarantine social-delivery; do
  mcli --config-dir /tmp/mcli mb --ignore-existing "local/$bucket" >/dev/null
  mcli --config-dir /tmp/mcli anonymous set none "local/$bucket" >/dev/null
  mcli --config-dir /tmp/mcli version enable "local/$bucket" >/dev/null
  mcli --config-dir /tmp/mcli encrypt set sse-s3 "local/$bucket" >/dev/null
done
while IFS= read -r line; do
  case "$line" in
    *FRONTEND_ORIGIN*) printf '    <AllowedOrigin>%s</AllowedOrigin>\n' "$FRONTEND_ORIGIN" ;;
    *) printf '%s\n' "$line" ;;
  esac
done </opt/storage/cors.xml >/tmp/cors.xml
mcli --config-dir /tmp/mcli cors set local/social-quarantine /tmp/cors.xml >/dev/null
mcli --config-dir /tmp/mcli ilm import local/social-quarantine </opt/storage/quarantine-lifecycle.json >/dev/null
for user in api worker; do
  case "$user" in api) password=$api_secret ;; worker) password=$worker_secret ;; esac
  mcli --config-dir /tmp/mcli admin user add local "social-$user" "$password" >/dev/null
  mcli --config-dir /tmp/mcli admin policy create local "social-$user" "/opt/storage/$user-policy.json" >/dev/null
  mcli --config-dir /tmp/mcli admin policy attach local "social-$user" --user "social-$user" >/dev/null
done
printf '%s\n' 'Private encrypted versioned storage initialized; admin/API/worker credentials separated.'

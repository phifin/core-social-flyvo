#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "$0")/../.."
mkdir -p .local/secrets
exec 9>.local/secrets/generation.lock
flock 9
if [[ ! -e .local/secrets/signing-private.pem ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out .local/secrets/signing-private.pem 2>/dev/null
fi
if [[ ! -e .local/secrets/signing-public.pem ]]; then
  openssl pkey -in .local/secrets/signing-private.pem -pubout -out .local/secrets/signing-public.pem
fi
if [[ ! -e .local/secrets/mail.b64 ]]; then
  openssl rand -base64 32 > .local/secrets/mail.b64
fi
if [[ ! -e .local/secrets/limiter.b64 ]]; then
  openssl rand -base64 32 > .local/secrets/limiter.b64
fi
printf '%s\n' 'Local keys are ready in .local/secrets; preserve them across restarts.'

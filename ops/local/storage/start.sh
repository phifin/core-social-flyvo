#!/bin/sh
set -eu
export MINIO_ROOT_USER=social-admin
MINIO_ROOT_PASSWORD=$(tr -d '\r\n' </run/secrets/storage-admin.b64)
export MINIO_ROOT_PASSWORD
MINIO_KMS_SECRET_KEY="social-local:$(tr -d '\r\n' </run/secrets/storage-kms.b64)"
export MINIO_KMS_SECRET_KEY
exec silo server /data --console-address :9001

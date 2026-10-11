#!/usr/bin/env bash
set -euo pipefail

# Keep this identical to the CI full-history scanner. Do not supply a broad config.
scanner_image='zricethezav/gitleaks:v8.28.0@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854'
fixture_dir=$(mktemp -d /tmp/social-secret-scan-fixture.XXXXXX)
cleanup() {
  rm -f "$fixture_dir/positive.txt" "$fixture_dir/report.json"
  rmdir "$fixture_dir"
}
trap cleanup EXIT

# Newly generated synthetic bytes, not an operational credential. The directory is
# outside git, with no .gitleaksignore; never print its value or an unredacted report.
printf 'api_key = "%s"\n' "$(openssl rand -hex 32)" > "$fixture_dir/positive.txt"
set +e
docker run --rm -v "$fixture_dir:/fixture" "$scanner_image" dir /fixture \
  --redact --no-banner --report-format json --report-path /fixture/report.json
scan_status=$?
set -e
if [[ $scan_status -ne 1 ]] || ! rg --quiet '"RuleID": "generic-api-key"' "$fixture_dir/report.json"; then
  echo 'Synthetic generic-api-key detection regression failed' >&2
  exit 1
fi
echo 'Synthetic generic-api-key positive control passed (redacted, outside git)'

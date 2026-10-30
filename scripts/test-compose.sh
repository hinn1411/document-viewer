#!/usr/bin/env bash
# T04: checks that the Compose stack comes up healthy and serves the demo-VIN table with the shared stubs.
# Local only, not in CI. Needs Docker, jq and free ports 5432, 8081 and 8082, so stop your own stack first.
# Run from anywhere: bash scripts/test-compose.sh
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
compose() { docker compose -p docviewer-test -f "$ROOT/docker-compose.yml" "$@"; }
trap 'compose down -v >/dev/null 2>&1' EXIT
failures=0

pass() { echo "PASS  $1"; }
fail() {
  echo "FAIL  $1 ($2)"
  failures=$((failures + 1))
}

# mounted <service> <folder>: the service has the stub folder as its WireMock root
mounted() {
  compose config --format json 2>/dev/null | jq -e --arg s "$1" --arg f "$ROOT/$2" \
    '.services[$s].volumes // [] | any(.source == $f and .target == "/home/wiremock")' >/dev/null
}

# demo <source> <path> <expected-status> <jq-filter>: calls the mock with no auth header
demo() {
  local name="$1 $2" port status
  [ "$1" = sales ] && port=8081 || port=8082
  status="$(curl -s -m 10 -o "$body" -w '%{http_code}' "http://localhost:$port$2")"
  if [ "$status" != "$3" ]; then
    fail "everyDemoVinRespondsAsInTable: $name" "expected $3, got $status"
    return
  fi
  if [ -n "$4" ] && ! jq -e "$4" "$body" >/dev/null 2>&1; then
    fail "everyDemoVinRespondsAsInTable: $name" "body doesn't match $4"
    return
  fi
  pass "everyDemoVinRespondsAsInTable: $name"
}

echo "one set of stubs"
if mounted sales-api mocks/sales && mounted service-api mocks/service; then
  pass "composeMountsMockFolders"
else
  fail "composeMountsMockFolders" "sales-api or service-api doesn't mount its mocks/ folder at /home/wiremock"
fi
copies="$(find "$ROOT/src" -type d \( -name mappings -o -name __files \))"
if [ -z "$copies" ]; then
  pass "noStubCopiesInTestResources"
else
  fail "noStubCopiesInTestResources" "found $copies"
fi

echo "stack"
compose up -d --wait --wait-timeout 120 >/dev/null 2>&1
healthy="$(compose ps --format json 2>/dev/null | jq -rs 'flatten | map(select(.Health == "healthy") | .Service) | sort | join(" ")')"
if [ "$healthy" = "postgres sales-api service-api" ]; then
  pass "allServicesHealthy"
else
  fail "allServicesHealthy" "healthy services: '${healthy}'"
fi

echo "demo VINs"
body="$(mktemp)"
trap 'compose down -v >/dev/null 2>&1; rm -f "$body"' EXIT
P=WVWZZZ1JZXW00000
HAS_DOCS='.deals[0].documents | length > 0'

demo sales "/sales/v1/deals?vin=${P}1" 200 "$HAS_DOCS"
demo service "/service/api/vehicles/${P}1/records" 200 'length > 0'
demo sales "/sales/v1/deals?vin=${P}2" 200 "$HAS_DOCS"
demo sales "/sales/v1/deals?vin=${P}3" 200 "$HAS_DOCS"
demo service "/service/api/vehicles/${P}3/records" 503 ''
demo sales "/sales/v1/deals?vin=${P}4" 503 ''
demo service "/service/api/vehicles/${P}4/records" 503 ''
demo sales "/sales/v1/deals?vin=${P}5" 200 '.deals == []'
demo service "/service/api/vehicles/${P}5/records" 200 '. == []'

# The 5 s delay stub: nothing back after 4 s, then a normal response
slow="/service/api/vehicles/${P}2/records"
if [ "$(curl -s -m 4 -o /dev/null -w '%{http_code}' "http://localhost:8082$slow")" = "000" ]; then
  demo service "$slow" 200 'length > 0'
else
  fail "everyDemoVinRespondsAsInTable: service $slow" "answered within 4 s, expected about 5 s"
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "All Compose checks passed"
else
  echo "$failures Compose check(s) failed"
  exit 1
fi

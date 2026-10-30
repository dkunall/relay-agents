# Shared helpers for the demo script. Source, don't run.
BASE=${BASE:-http://localhost:7790}
OAUTH=${OAUTH:-http://localhost:7791}
ORDERS=${ORDERS:-http://localhost:7792}
FIXTURES=${FIXTURES:-$(dirname "${BASH_SOURCE[0]}")/../src/test/resources/fixtures}

# The app validates the issuer as http://oauth:8080/relay (its in-network name),
# and mock-oauth2-server derives the issuer from the Host header.
token() {
  curl -sf -H "Host: oauth:8080" -d grant_type=client_credentials -d "client_id=$1" -d client_secret=unused \
    "$OAUTH/relay/token" | jq -r .access_token
}

call() {
  local tok=$1 method=$2 path=$3 body=${4:-}
  shift 4 || shift $#
  curl -s -X "$method" "$BASE$path" -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
    "$@" ${body:+-d "$body"}
}

# Polls until the run leaves the active states and prints it.
wait_run() {
  local tok=$1 id=$2 run status
  for _ in $(seq 1 150); do
    run=$(call "$tok" GET "/v1/runs/$id")
    status=$(jq -r .status <<<"$run")
    case $status in
      SUCCEEDED|FAILED|CANCELLED|BUDGET_EXCEEDED|WAITING_APPROVAL) echo "$run"; return 0 ;;
    esac
    sleep 0.2
  done
  echo "run $id stuck in $status" >&2
  return 1
}

now_ms() { date +%s%3N; }

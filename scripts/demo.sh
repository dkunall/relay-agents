#!/usr/bin/env bash
# Walks through the support-triage scenario against the compose stack. Needs curl, jq and openssl.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

check() { if [ "$1" != "$2" ]; then echo "FAIL: $3 (got '$1', want '$2')" >&2; exit 1; fi; echo "ok   $3"; }

asha=$(token dev-asha)
ravi=$(token lead-ravi)
lena=$(token dev-lena)

# The orders API fails once with 503 before answering, so the HTTP tool's retry shows up in the events.
curl -sf -X POST "$ORDERS/__admin/reset" >/dev/null
curl -sf -X POST "$ORDERS/__admin/mappings" -d '{"scenarioName": "flaky", "requiredScenarioState": "Started",
  "newScenarioState": "up", "request": {"method": "GET", "url": "/orders/O-5003"}, "response": {"status": 503}}' >/dev/null
curl -sf -X POST "$ORDERS/__admin/mappings" -d '{"scenarioName": "flaky", "requiredScenarioState": "up",
  "request": {"method": "GET", "url": "/orders/O-5003", "headers": {"Authorization": {"equalTo": "Bearer demo-orders-token"}}},
  "response": {"status": 200, "jsonBody": {"id": "O-5003", "status": "SHIPPED", "carrier": "Delhivery"}}}' >/dev/null

echo "== agents, knowledge base, workflow"
call "$asha" PUT /v1/agents/triage "$(cat "$FIXTURES/agent-triage.json")" >/dev/null
call "$asha" PUT /v1/agents/support "$(cat "$FIXTURES/agent-support.json")" >/dev/null
kb=$(call "$asha" POST /v1/kb "{\"name\": \"policies-$(date +%s)\"}" | jq -r .id)
jq -c '.[]' "$FIXTURES/kb-docs.json" | while read -r doc; do call "$asha" POST "/v1/kb/$kb/documents" "$doc" >/dev/null; done
# The default embedder hashes words, so the query has to share vocabulary with the document (see README).
hit=$(call "$asha" POST "/v1/kb/$kb/search" '{"query": "refund for a parcel damaged in transit", "k": 1}' | jq -r '.[0].title')
check "$hit" "Refunds for damaged parcels" "hybrid search finds the refund policy"
wf=$(call "$asha" POST /v1/workflows "$(sed "s/__KB__/$kb/" "$FIXTURES/workflow-support.json")" | jq -r .id)

echo "== status question: agent queries the tenant's sandbox orders table"
t0=$(now_ms)
run=$(call "$asha" POST "/v1/workflows/$wf/runs" '{"input": {"message": "Where is my order O-5003?"}}' \
  -H "Idempotency-Key: demo-$(date +%s)-status" | jq -r .id)
done=$(wait_run "$asha" "$run")
echo "     start to SUCCEEDED: $(( $(now_ms) - t0 )) ms (includes 200 ms client polling)"
check "$(jq -r .status <<<"$done")" SUCCEEDED "status run succeeds"
jq -r '.output' <<<"$done"

echo "== refund: needs a second person's approval"
run=$(call "$asha" POST "/v1/workflows/$wf/runs" \
  '{"input": {"message": "My order O-5002 arrived damaged, I want a refund", "amountMinor": 129900}}' | jq -r .id)
check "$(wait_run "$asha" "$run" | jq -r .status)" WAITING_APPROVAL "refund pauses for approval"
code=$(call "$asha" POST "/v1/runs/$run/approvals/refund" '{"approve": true}' -o /dev/null -w '%{http_code}')
check "$code" 403 "requester cannot approve"
call "$ravi" POST "/v1/runs/$run/approvals/refund" '{"approve": true, "comment": "photo checked"}' >/dev/null
check "$(wait_run "$asha" "$run" | jq -r .status)" SUCCEEDED "refund runs after lead approval"
code=$(call "$ravi" POST "/v1/runs/$run/approvals/refund" '{"approve": true}' -o /dev/null -w '%{http_code}')
check "$code" 409 "second approval is rejected"

echo "== replay from the recorded LLM and tool outputs"
replay=$(call "$asha" POST "/v1/runs/$run/replay" '{"mode": "REPLAY_RECORDED"}' | jq -r .id)
replayed=$(wait_run "$asha" "$replay")
check "$(jq -r .status <<<"$replayed")" SUCCEEDED "replay succeeds"
check "$(jq -r .usage.costMicros <<<"$replayed")" 0 "replay costs nothing"
check "$(call "$asha" GET "/v1/runs/$replay/diff" | jq -r .sameOutput)" true "replay output matches"

echo "== HTTP tool with a retry on 503"
call "$asha" POST /v1/tools/http '{"name": "order_lookup", "description": "Fetch an order from the orders API",
  "schema": {"type": "object", "required": ["orderId"], "properties": {"orderId": {"type": "string"}}},
  "scopes": ["orders.read"], "method": "GET", "urlTemplate": "http://orders-api:8080/orders/{orderId}",
  "headers": {"Authorization": "Bearer {{secret.ORDERS_API_TOKEN}}"}}' >/dev/null
fetch=$(call "$asha" POST /v1/workflows '{"name": "fetch-order", "steps": [{"id": "fetch", "type": "TOOL",
  "tool": "order_lookup", "grants": ["orders.read"], "args": {"orderId": "{{input.orderId}}"},
  "retry": {"maxAttempts": 3, "initialBackoffMs": 200, "maxBackoffMs": 1000}}]}' | jq -r .id)
run=$(call "$asha" POST "/v1/workflows/$fetch/runs" '{"input": {"orderId": "O-5003"}}' | jq -r .id)
done=$(wait_run "$asha" "$run")
check "$(jq -r .status <<<"$done")" SUCCEEDED "fetch succeeds"
check "$(jq -r '.steps[0].attempt' <<<"$done")" 2 "fetch needed a second attempt"
leaks=$(call "$asha" GET "/v1/runs/$run/events" | grep -c demo-orders-token || true)
check "$leaks" 0 "the API token never appears in run events"

echo "== signed webhook, delivered twice"
# Webhook runs get the request body under input.payload.
classify=$(call "$asha" POST /v1/workflows '{"name": "classify-ticket", "steps": [{"id": "classify", "type": "AGENT",
  "agent": "triage", "prompt": "{{input.payload.message}}"}]}' | jq -r .id)
hook=$(call "$asha" POST /v1/triggers "{\"workflowId\": \"$classify\", \"type\": \"WEBHOOK\", \"secretName\": \"HOOK_SECRET\",
  \"input\": {\"source\": \"helpdesk\"}}" | jq -r .webhookPath)
body='{"message": "Where is O-5001?"}'
ts=$(date +%s)
sig="sha256=$(printf '%s.%s' "$ts" "$body" | openssl dgst -sha256 -hmac demo-hook-secret -hex | awk '{print $NF}')"
deliver() { curl -s -X POST "$BASE$hook" -H 'Content-Type: application/json' -H "X-Relay-Timestamp: $ts" \
  -H "X-Relay-Signature: $1" -H "X-Relay-Delivery: helpdesk-42" -d "$body"; }
first=$(deliver "$sig" | jq -r .runId)
second=$(deliver "$sig" | jq -r .runId)
check "$second" "$first" "redelivery returns the same run"
check "$(wait_run "$asha" "$first" | jq -r .output.classify.intent)" status "webhook run classifies the ticket"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE$hook" -H "X-Relay-Timestamp: $ts" \
  -H "X-Relay-Signature: sha256=00" -H "X-Relay-Delivery: helpdesk-43" -d "$body")
check "$code" 403 "bad signature is rejected"

echo "== tenant isolation"
code=$(call "$lena" GET "/v1/runs/$first" "" -o /dev/null -w '%{http_code}')
check "$code" 404 "globex cannot see acme's run"

echo "== cost per day (scripted provider, made-up prices)"
call "$asha" GET /v1/costs | jq -c '.[]'
echo "demo passed"

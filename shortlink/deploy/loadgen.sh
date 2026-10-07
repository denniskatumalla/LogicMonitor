#!/usr/bin/env bash
# Realistic-looking traffic for the demo: mostly redirects, some lookups and
# creates, and a small share of client mistakes (404s, 400s). Volume follows
# a 60-minute cycle so graphs have shape rather than a flat line.
set -uo pipefail

TARGET=${TARGET:-http://127.0.0.1:8080}
codes=()

destinations=(
  https://www.logicmonitor.com/
  https://www.logicmonitor.com/support
  https://aws.amazon.com/ec2/
  https://openjdk.org/jeps/444
  https://opentelemetry.io/docs/languages/java/
  https://en.wikipedia.org/wiki/URL_shortening
  https://github.com/
  https://www.rfc-editor.org/rfc/rfc9110
)

req() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$@" || echo 000; }

create() {
  local url=$1 body code
  body=$(curl -s --max-time 5 -H 'Content-Type: application/json' \
    -d "{\"url\":\"${url}\"}" "${TARGET}/api/links") || return 0
  code=$(sed -n 's/.*"code":"\([A-Za-z0-9]*\)".*/\1/p' <<<"$body")
  [[ -n $code ]] && codes+=("$code")
  return 0
}

pick() { echo "${codes[RANDOM % ${#codes[@]}]}"; }

# Pause between requests, in ms: busy, normal, then quiet third of each hour.
pause_ms() {
  local minute=$((10#$(date +%M)))
  if   (( minute < 20 )); then echo $((150 + RANDOM % 400))
  elif (( minute < 40 )); then echo $((400 + RANDOM % 800))
  else                         echo $((900 + RANDOM % 1500))
  fi
}

until [[ $(req "${TARGET}/health") =~ ^(200|503)$ ]]; do sleep 5; done
for d in "${destinations[@]}"; do create "$d"; done

while true; do
  if (( ${#codes[@]} == 0 )); then create "${destinations[0]}"; sleep 5; continue; fi
  roll=$((RANDOM % 100))
  if   (( roll < 82 )); then
    [[ $(req "${TARGET}/$(pick)") == 404 ]] && codes=()     # store was reset: start over
  elif (( roll < 90 )); then req "${TARGET}/api/links/$(pick)" > /dev/null
  elif (( roll < 94 )); then create "${destinations[RANDOM % ${#destinations[@]}]}?utm_source=demo&n=${RANDOM}"
  elif (( roll < 97 )); then req "${TARGET}/zz$((RANDOM % 99999))" > /dev/null                      # 404
  elif (( roll < 99 )); then req -H 'Content-Type: application/json' -d '{"url":"not a url"}' "${TARGET}/api/links" > /dev/null   # 400
  else                       req "${TARGET}/" > /dev/null
  fi
  ms=$(pause_ms)
  sleep "$(printf '%d.%03d' $((ms / 1000)) $((ms % 1000)))"
done

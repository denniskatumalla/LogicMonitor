#!/usr/bin/env bash
# Customer-like traffic for the demo: mostly people checking a ticket, their
# ZIP or the service-area view, some page loads, a steady trickle of new
# reports (a few through the no-JavaScript form), and a small share of
# customer mistakes (unknown tickets, ZIPs outside the territory, bad phone
# numbers). Volume follows a 60-minute cycle so graphs have shape.
set -uo pipefail

TARGET=${TARGET:-http://127.0.0.1:8080}
tickets=()

notes=(
  "Lights flickered then went out"
  "Loud bang from the transformer on the pole"
  "Whole street is dark"
  "Tree branch on the line behind the house"
  "Power out since the thunderstorm"
  ""
  ""
)

req() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@" || echo 000; }

zip() { printf '%05d' $((10 + RANDOM % 60)); }
phone() { (( RANDOM % 3 == 0 )) && printf '800-555-01%02d' $((RANDOM % 100)); }

remember() {
  local id
  id=$(sed -n 's/.*"id":"\(EPL-[A-Z0-9]*\)".*/\1/p' <<<"$1")
  [[ -n $id ]] || return 0
  tickets+=("$id")
  (( ${#tickets[@]} > 200 )) && tickets=("${tickets[@]:1}")
  return 0
}

report() {
  local z note ph
  z=$(zip); note=${notes[RANDOM % ${#notes[@]}]}; ph=$(phone)
  if (( RANDOM % 4 == 0 )); then
    req --data-urlencode "zip=${z}" --data-urlencode "address=$((RANDOM % 900 + 100)) Bay St" \
        --data-urlencode "phone=${ph}" --data-urlencode "notes=${note}" "${TARGET}/report" > /dev/null
  else
    remember "$(curl -s --max-time 10 -H 'Content-Type: application/json' \
      -d "{\"zip\":\"${z}\",\"phone\":\"${ph}\",\"notes\":\"${note}\"}" "${TARGET}/api/reports")"
  fi
}

page() {
  local pages=(/ /status /areas)
  req "${TARGET}${pages[RANDOM % 3]}" > /dev/null
  if (( RANDOM % 3 == 0 )); then
    req "${TARGET}/static/app.css" > /dev/null
    req "${TARGET}/static/app.js" > /dev/null
  fi
}

pick() { echo "${tickets[RANDOM % ${#tickets[@]}]}"; }

# Pause between requests, in ms: busy, normal, then quiet third of each hour.
pause_ms() {
  local minute=$((10#$(date +%M)))
  if   (( minute < 20 )); then echo $((150 + RANDOM % 400))
  elif (( minute < 40 )); then echo $((400 + RANDOM % 800))
  else                         echo $((900 + RANDOM % 1500))
  fi
}

until [[ $(req "${TARGET}/health") =~ ^(200|503)$ ]]; do sleep 5; done
for _ in 1 2 3 4 5; do report; done

while true; do
  if (( ${#tickets[@]} == 0 )); then report; sleep 5; continue; fi
  roll=$((RANDOM % 100))
  if   (( roll < 40 )); then req "${TARGET}/api/reports/$(pick)" > /dev/null
  elif (( roll < 54 )); then req "${TARGET}/api/zip/$(zip)" > /dev/null
  elif (( roll < 72 )); then req "${TARGET}/api/areas" > /dev/null
  elif (( roll < 90 )); then page
  elif (( roll < 95 )); then report
  elif (( roll < 98 )); then req "${TARGET}/api/reports/EPL-$((RANDOM % 9000 + 1000))XY" > /dev/null    # 404
  elif (( roll < 99 )); then req -H 'Content-Type: application/json' -d '{"zip":"99999"}' "${TARGET}/api/reports" > /dev/null   # 400
  else                       req -H 'Content-Type: application/json' -d '{"zip":"00012","phone":"call me"}' "${TARGET}/api/reports" > /dev/null
  fi
  ms=$(pause_ms)
  sleep "$(printf '%d.%03d' $((ms / 1000)) $((ms % 1000)))"
done

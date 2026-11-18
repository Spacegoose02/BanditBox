#!/usr/bin/env bash
# Measures BanditBox's idle memory with `dumpsys meminfo` and fails over the budget.
#
#   tools/ram-check.sh [package] [budget_mb]
#
# Works against any device adb can see: the CI emulator, or a real TV by hand
# (`adb connect <tv-ip>` first). Measure a release build: debug builds are much bigger.
# Environment: SETTLE_SECONDS (default 20), SAMPLES (default 5), REPORT (default ram-report.txt).
set -euo pipefail

PKG="${1:-com.spacegoose.banditbox}"
BUDGET_MB="${2:-80}"
SETTLE_SECONDS="${SETTLE_SECONDS:-20}"
SAMPLES="${SAMPLES:-5}"
REPORT="${REPORT:-ram-report.txt}"
ACTIVITY="com.spacegoose.banditbox.HomeActivity"

# Total PSS in KB. Newer Android prints "TOTAL PSS:" in the App Summary; older prints a
# "TOTAL" row whose first number is the PSS total.
total_pss_kb() {
  local out
  out="$(adb shell dumpsys meminfo "$PKG")"
  local kb
  kb="$(printf '%s\n' "$out" | tr -d ',' | awk '/TOTAL PSS:/ { for (i = 1; i <= NF; i++) if ($i == "PSS:") { print $(i + 1); exit } }')"
  if [ -z "$kb" ]; then
    kb="$(printf '%s\n' "$out" | tr -d ',' | awk '$1 == "TOTAL" && $2 ~ /^[0-9]+$/ { print $2; exit }')"
  fi
  echo "$kb"
}

: > "$REPORT"
log() { echo "$*" | tee -a "$REPORT"; }

adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACTIVITY" > /dev/null
log "Started $PKG; letting it settle for ${SETTLE_SECONDS}s"
sleep "$SETTLE_SECONDS"

values=()
for i in $(seq 1 "$SAMPLES"); do
  kb="$(total_pss_kb)"
  if [ -z "$kb" ]; then
    log "Couldn't read TOTAL PSS from dumpsys meminfo. Is $PKG running?"
    adb shell dumpsys meminfo "$PKG" | tee -a "$REPORT"
    exit 2
  fi
  values+=("$kb")
  log "Sample $i: $((kb / 1024)) MB PSS ($kb KB)"
  sleep 2
done

# Median, so one GC spike either way doesn't decide the result.
median_kb="$(printf '%s\n' "${values[@]}" | sort -n | awk '{ v[NR] = $1 } END { print v[int((NR + 1) / 2)] }')"
median_mb=$((median_kb / 1024))

log ""
log "Last full dumpsys meminfo:"
adb shell dumpsys meminfo "$PKG" >> "$REPORT"

log ""
if [ "$median_kb" -gt $((BUDGET_MB * 1024)) ]; then
  log "FAIL: idle median ${median_mb} MB PSS is over the ${BUDGET_MB} MB budget"
  exit 1
fi
log "OK: idle median ${median_mb} MB PSS, budget ${BUDGET_MB} MB"

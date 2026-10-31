#!/usr/bin/env bash
# Small load run against a running stack: concurrent attempts (latency, throughput, time until the adaptive
# consumer has applied them) and tutor questions (latency). Prints measurements only; the numbers depend on the
# machine and on everything else running on it. Synthetic learners. Tokens are never printed.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
LEARNERS=${LEARNERS:-20}
PER=${PER:-25}
ASKS=${ASKS:-2}
OUT=$(mktemp -d /tmp/mentorloop-bench.XXXXXX)
trap 'rm -rf "$OUT"' EXIT

run=$(date +%s)
INS=$(token "$INSTRUCTOR")
build_course "Bench $run"
items=("$I_ORDER:{\"choices\":[0]}" "$I_SIZE:{\"number\":2}" "$I_UNDO:{\"text\":\"a stack\"}" "$I_ORDER:{\"choices\":[1]}")
for ((l = 0; l < LEARNERS; l++)); do
  tok=$(token "bench$l.$run@learners.example.test")
  printf '%s' "$tok" > "$OUT/tok$l"
  expect 201 "$tok" POST "/v1/courses/$COURSE/enrollments" '{}' > /dev/null
done

echo "attempts: $LEARNERS learners x $PER answers, all learners at once"
t0=$(date +%s%N)
for ((l = 0; l < LEARNERS; l++)); do
  (
    tok=$(cat "$OUT/tok$l")
    for ((i = 0; i < PER; i++)); do
      pair=${items[$((i % 4))]}
      body=$(jq -nc --arg c "$(uuid)" --argjson a "${pair#*:}" '{clientAttemptId:$c,answer:$a,latencyMs:4000}')
      curl -s -o "$OUT/last$l" -w '%{http_code} %{time_total}\n' -X POST "$BASE/v1/items/${pair%%:*}/attempts" \
        -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' -d "$body" >> "$OUT/attempts"
    done
  ) &
done
wait
t1=$(date +%s%N)
ok=$(awk '$1 == 200' "$OUT/attempts" | wc -l)
awk '$1 == 200 {print $2 * 1000}' "$OUT/attempts" > "$OUT/ms"
echo "  $ok/$((LEARNERS * PER)) answered 200 in $(( (t1 - t0) / 1000000 )) ms wall: $(awk -v n="$ok" -v ms=$(( (t1 - t0) / 1000000 )) 'BEGIN {printf "%.0f", n * 1000 / ms}') attempts/s"
echo "  latency ms p50=$(percentile "$OUT/ms" 50) p95=$(percentile "$OUT/ms" 95) p99=$(percentile "$OUT/ms" 99) max=$(percentile "$OUT/ms" 100)"
# Every learner's last seq is PER (seqs are gapless per learner); wait until progress shows it applied.
for ((l = 0; l < LEARNERS; l++)); do
  tok=$(cat "$OUT/tok$l")
  until [[ $(curl -sf -H "Authorization: Bearer $tok" "$BASE/v1/courses/$COURSE/progress" | jq -r .appliedSeq) -ge $PER ]]; do
    sleep 0.05
  done
done
t2=$(date +%s%N)
echo "  all applied by the adaptive consumer $(( (t2 - t1) / 1000000 )) ms after the last answer returned"

echo "tutor: $LEARNERS learners x $ASKS questions, all at once (scripted provider, so this is retrieval + guard, no model)"
for ((l = 0; l < LEARNERS; l++)); do
  (
    tok=$(cat "$OUT/tok$l")
    for ((i = 0; i < ASKS; i++)); do
      curl -s -o /dev/null -w '%{http_code} %{time_total}\n' -X POST "$BASE/v1/courses/$COURSE/tutor/questions" \
        -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
        -d '{"question":"Why is undo history a stack?"}' >> "$OUT/tutor"
    done
  ) &
done
wait
awk '$1 == 200 {print $2 * 1000}' "$OUT/tutor" > "$OUT/tms"
echo "  $(wc -l < "$OUT/tms")/$((LEARNERS * ASKS)) answered 200; latency ms p50=$(percentile "$OUT/tms" 50) p95=$(percentile "$OUT/tms" 95) max=$(percentile "$OUT/tms" 100)"

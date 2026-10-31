#!/usr/bin/env bash
# End-to-end walk through a running stack (scripts/up.sh first). Synthetic course and learners; exits non-zero on
# the first check that fails. Tokens are kept in variables and never printed.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

run=$(date +%s)
LEARNER="asha.$run@learners.example.test"
PEER="ravi.$run@learners.example.test"
INS=$(token "$INSTRUCTOR"); ME=$(token "$LEARNER"); OTHER=$(token "$PEER")

echo "== instructor builds a two-concept course (queues needs stacks)"
build_course "Data Structures demo $run"
echo "  course $COURSE published with 2 lessons and 6 items"

echo "== the scripted model drafts items; drafts stay hidden until approved"
DRAFT=$(expect 201 "$INS" POST "/v1/courses/$COURSE/items:generate" '{"concept":"stacks","count":1}' | jq -r '.[0].item.id')
expect 201 "$ME" POST "/v1/courses/$COURSE/enrollments" '{}' > /dev/null
expect 201 "$OTHER" POST "/v1/courses/$COURSE/enrollments" '{}' > /dev/null
check "learner cannot see a draft" "$(call "$ME" GET "/v1/items/$DRAFT" | tail -1)" 404
check "draft approved" "$(expect 200 "$INS" POST "/v1/items/$DRAFT:approve" '{}' | jq -r .status)" APPROVED
expect 200 "$INS" POST "/v1/items/$DRAFT:retire" '{}' > /dev/null

echo "== learner path: lesson first, then practice; queues locked until stacks is mastered"
P=$(expect 200 "$ME" GET "/v1/courses/$COURSE/progress")
check "queues starts locked" "$(jq -r '.concepts[]|select(.key=="queues").state' <<< "$P")" LOCKED
check "next is the stacks lesson" "$(expect 200 "$ME" GET "/v1/courses/$COURSE/next" | jq -r .kind)" LESSON
expect 200 "$ME" POST "/v1/lessons/$L_STACKS:complete" '{}' > /dev/null
check "then practice" "$(expect 200 "$ME" GET "/v1/courses/$COURSE/next" | jq -r .kind)" PRACTICE

answer() { jq -n --arg c "$1" --argjson a "$2" '{clientAttemptId:$c,answer:$a,latencyMs:4000}'; }
A1=$(uuid)
R=$(expect 200 "$ME" POST "/v1/items/$I_ORDER/attempts" "$(answer "$A1" '{"choices":[1]}')")
check "wrong answer graded" "$(jq -r .correct <<< "$R")" false
echo "  explanation: $(jq -r .explanation <<< "$R")"
R2=$(expect 200 "$ME" POST "/v1/items/$I_ORDER/attempts" "$(answer "$A1" '{"choices":[1]}')")
check "retry with the same clientAttemptId replays seq" "$(jq -r .seq <<< "$R2")" "$(jq -r .seq <<< "$R")"
check "same id, different answer" "$(call "$ME" POST "/v1/items/$I_ORDER/attempts" "$(answer "$A1" '{"choices":[0]}')" | tail -1)" 422

echo "== grounded tutor (scripted provider: extractive answer from retrieved lesson chunks)"
T=$(expect 200 "$ME" POST "/v1/courses/$COURSE/tutor/questions" '{"question":"What order does a stack keep items in?"}')
check "answer is grounded" "$(jq -r .status <<< "$T")" GROUNDED
echo "  answer: $(jq -r .answer <<< "$T")"
echo "  citations: $(jq -c '[.citations[]|{n,label}]' <<< "$T")"
check "off-topic question refused" "$(expect 200 "$ME" POST "/v1/courses/$COURSE/tutor/questions" \
  '{"question":"Who won the cricket world cup in 1983?"}' | jq -r .status)" REFUSED

echo "== quiz: while it is open the tutor only gives hints"
Q=$(expect 201 "$ME" POST "/v1/courses/$COURSE/quizzes" '{}')
QUIZ=$(jq -r .id <<< "$Q")
echo "  quiz items: $(jq -c '[.questions[].stem]' <<< "$Q")"
check "tutor switches to hints" "$(expect 200 "$ME" POST "/v1/courses/$COURSE/tutor/questions" \
  '{"question":"Which order does a stack remove elements in?"}' | jq -r .mode)" HINT
for row in $(jq -r '.questions[].itemId' <<< "$Q"); do
  case $row in
    "$I_ORDER") a='{"choices":[0]}' ;; "$I_SIZE") a='{"number":2}' ;; "$I_UNDO") a='{"text":"A stack"}' ;; *) continue ;;
  esac
  expect 200 "$ME" POST "/v1/items/$row/attempts" "$(jq -n --arg c "$(uuid)" --argjson a "$a" --arg q "$QUIZ" \
    '{clientAttemptId:$c,answer:$a,latencyMs:4000,quizId:$q}')" > /dev/null
done
echo "  quiz score: $(expect 200 "$ME" POST "/v1/quizzes/$QUIZ:submit" '{}' | jq -r .score)"
check "another learner's quiz is not visible" "$(call "$OTHER" GET "/v1/quizzes/$QUIZ" | tail -1)" 404

echo "== mastery (Bayesian knowledge tracing, applied by the Kafka consumer)"
progress() { expect 200 "$ME" GET "/v1/courses/$COURSE/progress" | jq -r "$1"; }
until_eq 30 MASTERED progress '.concepts[]|select(.key=="stacks").state' > /dev/null
P=$(expect 200 "$ME" GET "/v1/courses/$COURSE/progress")
jq -r '.concepts[] | "  \(.key): \(.state) pKnown=\(.pKnown*1000|round/1000) attempts=\(.attempts)"' <<< "$P"

echo "== a borderline short answer waits for the instructor"
expect 200 "$ME" POST "/v1/items/$I_QORDER/attempts" "$(answer "$(uuid)" '{"choices":[1]}')" > /dev/null
expect 200 "$ME" POST "/v1/items/$I_COUNT/attempts" "$(answer "$(uuid)" '{"number":3}')" > /dev/null
W=$(expect 200 "$ME" POST "/v1/items/$I_WHY/attempts" "$(answer "$(uuid)" '{"text":"the first and earliest job goes out"}')")
check "outcome" "$(jq -r .outcome <<< "$W")" NEEDS_REVIEW
check "other learner cannot grade" "$(call "$OTHER" POST "/v1/attempts/$(jq -r .attemptId <<< "$W"):grade" '{"correct":true}' | tail -1)" 403
expect 200 "$INS" POST "/v1/attempts/$(jq -r .attemptId <<< "$W"):grade" '{"correct":true}' > /dev/null
check "graded twice" "$(call "$INS" POST "/v1/attempts/$(jq -r .attemptId <<< "$W"):grade" '{"correct":false}' | tail -1)" 409

echo "== certificate once every concept is mastered"
until_eq 30 true progress '.certificateCode != null' > /dev/null
CODE=$(expect 200 "$ME" GET "/v1/courses/$COURSE/progress" | jq -r .certificateCode)
echo "  certificate $CODE"
check "anyone can verify it" "$(expect 200 "" GET "/v1/certificates/$CODE" | jq -r .learner)" "$LEARNER"
until_eq 30 1 mails "$LEARNER" Certificate > /dev/null && echo "  ok   certificate mail with attachment in Mailpit"

echo "== spaced repetition: demo clock has 20-second days, so reviews fall due quickly"
until_eq 90 true progress '.reviewsDue > 0' > /dev/null
echo "  reviews due: $(expect 200 "$ME" GET "/v1/courses/$COURSE/progress" | jq -r .reviewsDue); next: $(expect 200 "$ME" GET "/v1/courses/$COURSE/next" | jq -r .kind)"
reminded() { [[ $(mails "$LEARNER" "Reviews due") -ge 1 ]] && echo yes; }
until_eq 120 yes reminded > /dev/null
echo "  ok   review reminder mail arrived: $(curl -sf -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:\"$LEARNER\"" | jq -r '[.messages[].Subject]|join(" | ")')"

echo "== instructor views"
D=$(expect 200 "$INS" GET "/v1/courses/$COURSE/dashboard")
echo "  enrolled=$(jq -r .enrolled <<< "$D"); per item: $(jq -c '[.items[]|select(.attempts>0)|{stem:.stem[0:24],attempts,correctRate}]' <<< "$D")"
check "learner cannot open the dashboard" "$(call "$ME" GET "/v1/courses/$COURSE/dashboard" | tail -1)" 403
echo "demo passed"

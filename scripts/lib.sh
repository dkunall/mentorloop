# Shared helpers for demo.sh and bench.sh. Source, don't run.
BASE=${BASE:-http://127.0.0.1:8870}
OAUTH=${OAUTH:-http://127.0.0.1:8871}
MAILPIT=${MAILPIT:-http://127.0.0.1:8872}
INSTRUCTOR=${INSTRUCTOR:-instructor@mentorloop.example.test}

# The app validates the issuer as http://oauth:8080/mentorloop (its in-network name), and mock-oauth2-server
# derives the issuer from the Host header. The client_id becomes the token subject.
token() {
  curl -sf -H "Host: oauth:8080" -d grant_type=client_credentials -d "client_id=$1" -d client_secret=unused \
    -d scope=mentorloop "$OAUTH/mentorloop/token" | jq -r .access_token
}

# call TOKEN METHOD PATH [BODY] [EXTRA_CURL_ARGS...]; prints the body, then the status on its own line.
call() {
  local tok=$1 method=$2 path=$3 body=${4:-}
  shift 4 2> /dev/null || shift $#
  curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" ${tok:+-H "Authorization: Bearer $tok"} \
    -H 'Content-Type: application/json' ${body:+-d "$body"} "$@"
}

# expect STATUS TOKEN METHOD PATH [BODY] [EXTRA...]; prints the body or fails loudly (never prints the token).
expect() {
  local want=$1 out status
  shift
  out=$(call "$@")
  status=${out##*$'\n'}
  if [[ $status != "$want" ]]; then
    echo "FAIL: $2 $3 -> $status (wanted $want): ${out%$'\n'*}" >&2
    exit 1
  fi
  printf '%s' "${out%$'\n'*}"
}

check() {
  if [[ $2 == "$3" ]]; then echo "  ok   $1"; else echo "  FAIL $1: got '$2', wanted '$3'" >&2; exit 1; fi
}

# until_eq SECONDS WANT CMD...; reruns CMD until it prints WANT, or fails after SECONDS.
until_eq() {
  local limit=$1 want=$2 got=""
  shift 2
  for ((i = 0; i < limit; i++)); do
    got=$("$@" 2> /dev/null || true)
    [[ $got == "$want" ]] && { echo "$got"; return 0; }
    sleep 1
  done
  echo "timed out after ${limit}s; last value '$got', wanted '$want'" >&2
  return 1
}

# mails TO SUBJECT_PREFIX; number of mails to TO whose subject starts with SUBJECT_PREFIX.
mails() {
  curl -sf -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:\"$1\"" \
    | jq --arg s "$2" '[.messages[] | select(.Subject | startswith($s))] | length'
}

uuid() { cat /proc/sys/kernel/random/uuid; }

percentile() {
  sort -n "$1" | awk -v p="$2" '{a[NR]=$1} END {i=int((p/100)*NR+0.999); if (i<1) i=1; print a[i]}'
}

# build_course TITLE; with $INS set, creates and publishes the two-concept course used by demo.sh and bench.sh.
# Sets COURSE, MODULE, L_STACKS and the item ids I_ORDER I_SIZE I_UNDO I_QORDER I_WHY I_COUNT.
build_course() {
  local run_title=$1
COURSE=$(expect 201 "$INS" POST /v1/courses "$(jq -n --arg t "$run_title" '{title:$t}')" | jq -r .id)
expect 201 "$INS" POST "/v1/courses/$COURSE/concepts" '{"concepts":[
  {"key":"stacks","name":"Stacks","prerequisites":[]},
  {"key":"queues","name":"Queues","prerequisites":["stacks"]}]}' > /dev/null
MODULE=$(expect 201 "$INS" POST "/v1/courses/$COURSE/modules" '{"title":"Linear structures"}' | jq -r .id)
lesson() { jq -n --arg c "$1" --arg t "$2" --arg b "$3" '{concept:$c,title:$t,body:$b}'; }
L_STACKS=$(expect 201 "$INS" POST "/v1/modules/$MODULE/lessons" "$(lesson stacks Stacks '# What a stack is
A **stack** keeps items in last in, first out order. The last element pushed is the first one popped.

# Where stacks show up
Undo history in an editor is a stack: the most recent edit is undone first.')" | jq -r .id)
expect 201 "$INS" POST "/v1/modules/$MODULE/lessons" "$(lesson queues Queues '# What a queue is
A **queue** keeps items in first in, first out order. Elements join at the back and leave from the front.

# Where queues show up
A printer works through jobs with a queue, so the earliest job prints first.')" > /dev/null
item() { expect 201 "$INS" POST "/v1/courses/$COURSE/items" "$1" | jq -r .item.id; }
I_ORDER=$(item '{"type":"MCQ","concepts":["stacks"],"stem":"Which order does a stack remove elements in?",
  "options":["Last in, first out","First in, first out","Smallest first","Random"],"key":{"choices":[0]},
  "explanation":"A stack pops the element pushed most recently.","difficulty":-0.5}')
I_SIZE=$(item '{"type":"NUMERIC","concepts":["stacks"],"stem":"Push 3 onto an empty stack, pop 1. How many are left?",
  "key":{"value":2,"tolerance":0},"explanation":"Three in, one out leaves two.","difficulty":0}')
I_UNDO=$(item '{"type":"SHORT_ANSWER","concepts":["stacks"],"stem":"Which data structure backs an editor undo history?",
  "key":{"accepted":["stack","a stack"],"keywords":[]},"explanation":"Most recent edit first is stack order.","difficulty":0.5}')
I_QORDER=$(item '{"type":"MCQ","concepts":["queues"],"stem":"Which order does a queue remove elements in?",
  "options":["Last in, first out","First in, first out","Largest first","Random"],"key":{"choices":[1]},
  "explanation":"A queue serves the element that has waited longest.","difficulty":-0.5}')
I_WHY=$(item '{"type":"SHORT_ANSWER","concepts":["queues"],"stem":"Why does a printer use a queue for jobs?",
  "key":{"accepted":["so the earliest job prints first"],"keywords":["earliest","first"]},
  "explanation":"The job sent first should print first.","difficulty":0.8}')
I_COUNT=$(item '{"type":"NUMERIC","concepts":["queues"],"stem":"Enqueue 5 jobs, dequeue 2. How many are waiting?",
  "key":{"value":3,"tolerance":0},"explanation":"Five in, two out.","difficulty":0.2}')
expect 200 "$INS" POST "/v1/courses/$COURSE:publish" '{}' > /dev/null
}

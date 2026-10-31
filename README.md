# MentorLoop

A learning platform backend: courses built from concepts with prerequisites, adaptive practice driven by a mastery model, spaced-repetition reviews, grading (automatic and by an instructor), certificates, and an AI tutor that answers only from the course's own lessons and cites them.

Java 21, Spring Boot 3.5, PostgreSQL with pgvector, Kafka, Redis, Mailpit, and a mock OAuth2 server. All data in the repo, the tests and the demo is synthetic.

**No paid model is needed or used.** The tutor talks to an `LlmPort`. The default provider is a deterministic scripted one, which extracts sentences rather than reasoning; tests, the demo and CI all run on it. There is also an OpenAI-compatible adapter (`/v1/chat/completions`), tested only against WireMock in this repo. It has never been run against a real model here.

## What it does

**Courses.** An instructor creates a course, modules, lessons and concepts. Concepts form a prerequisite graph (cycles are rejected), and a concept unlocks for a learner when all its prerequisites are mastered. Items are multiple choice, numeric (with a tolerance) or short answer. Answer keys never leave the server: learners see stems and options only.

**Item drafts from the model.** `items:generate` asks the model for questions about a concept. They are stored as `DRAFT`, invisible to learners until an instructor approves them (they then go `LIVE`). Approve, reject and retire are compare-and-set, so eight concurrent approvals give one 200 and seven 409s.

**Attempts and grading.** A learner submits an answer with a `clientAttemptId`. A retry with the same id and the same answer replays the stored result; the same id with a different answer is a 422. Each learner's attempts get a gapless sequence number. Short answers are exact-match after normalisation; an answer that contains all the key's keywords but isn't an exact match goes to `NEEDS_REVIEW` for an instructor rather than being guessed at by fuzzy matching. An instructor grades it once (a second grade is 409).

**Mastery (Bayesian knowledge tracing).** Each attempt is published through a transactional outbox to Kafka, keyed by learner. A consumer updates `p_known` per concept with BKT (prior 0.2, learn 0.15, slip 0.1, guess 0.2) and marks a concept mastered at 0.95. BKT depends on order, so events are stored first and applied strictly in the learner's sequence: an early event waits until the gap is filled, and duplicates are no-ops. A test replays a learner's history reversed and twice over and gets the same mastery and schedule.

**What next.** `GET /next` returns one activity: a due review first, then a concept already in progress (lowest `p_known` first), then the next unlocked concept (its lesson if unread, else practice). Practice picks the item whose Rasch difficulty should give the learner about a 70% chance of success.

**Reviews (SM-2).** Every answered item gets an SM-2 review card. Due counts are cached in Redis with a short TTL. A scheduled job mails a reminder ("Reviews due in <course>") at most once per learner per course per day slot.

**Difficulty calibration.** `calibration:run` fits a Rasch model to the course's attempts (when an item has enough of them) and stores a new difficulty version. Runs take a course lock, so concurrent runs produce consecutive versions instead of overwriting each other.

**Quizzes.** A timed quiz over chosen items, scored on submit. A job closes overdue quizzes. Other learners get a 404 for a quiz that isn't theirs.

**Certificates.** Once every concept is mastered, the learner gets a certificate code (for example `ML-4S23-V5VM-LLVR`). Anyone can verify it at `GET /v1/certificates/{code}`, and a mail with the certificate goes out.

**Instructor dashboard.** Enrolment and per-item attempts and correct rate. It's for the course owner only.

**Tutor.**
- Lessons and worked examples are chunked and indexed twice: Postgres full-text and a pgvector column.
- The embedding is a deterministic feature-hashing vector (terms, bigrams, character trigrams), so no embedding API is needed. It's lexical: it handles plurals and word order, not synonyms or paraphrase.
- A question retrieves the top 4 chunks by reciprocal rank fusion of the two lists. The model gets them numbered and must cite them as `[n]`.
- Markers that point outside the context are removed. A reply with no valid citation isn't shown as an answer: the learner gets the search results instead (`DEGRADED_SEARCH`), or a refusal if the model said the course doesn't cover it (`REFUSED`).
- If the model is down or times out, learners get the search results, not an error.
- While a learner has a quiz open, the tutor only hints. This is enforced on the server and doesn't rely on the model behaving: worked examples are left out of retrieval, the model is asked for a hint, and any reply containing an open quiz item's correct option text, accepted answer or numeric value is replaced by a generic hint (`GUARD_BLOCKED`).
- Each learner has a per-minute budget (10 by default). Over it, the API returns 429 with `Retry-After`.
- Transcripts are kept for 90 days.

## Layout

Hexagonal, one package per module, each with `domain`, `application` and `adapter`:

```
com.mentorloop
├── course        courses, modules, lessons, concepts and the prerequisite graph
├── assessment    items, graders, attempts, quizzes, manual grading, Rasch calibration
├── adaptive      BKT, SM-2, the ordered attempt consumer, progress, next activity, dashboard, certificates
├── tutor         chunking, hashing embedder, hybrid retrieval, citations, quiz-mode guard, rate limit
├── llm           LlmPort with the scripted provider and the OpenAI-compatible adapter
├── notification  review reminders and certificate mail
├── outbox        transactional outbox and relay to Kafka
├── shared        security, errors (RFC 9457 problem details), clock, text utilities
└── bench         offline benches (no Spring)
```

ArchUnit enforces seven rules: domain code is plain Java (no Spring, Jakarta, JDBC or Jackson); application code doesn't depend on adapters or on JDBC, web, Kafka, Redis or HTTP clients; the tutor knows quizzes only through its own port, never the assessment module's types; only the LLM adapters know a model is remote; modules have no dependency cycles; controllers live in adapters and aren't public.

## Run it

You need Docker with Compose v2, plus `curl`, `jq`, `awk` and `openssl` for the scripts.

```
./scripts/up.sh        # builds the image, writes fresh secrets to ~/.config/mentorloop/compose.env (mode 600), waits for health
./scripts/demo.sh      # walks a course end to end, checking each step
./scripts/bench.sh     # rough attempt and tutor timings
./scripts/down.sh      # stops everything and removes the volumes
```

Ports (all bound to 127.0.0.1):
- 8870: the API
- 8871: a mock OAuth2 server ([mock-oauth2-server](https://github.com/navikt/mock-oauth2-server))
- 8872: Mailpit, where you can read the reminder and certificate mails

The compose stack runs a demo clock: a "day" is 20 seconds and the reminder job runs every 30 seconds, so reviews fall due while you watch.

To try a real OpenAI-compatible endpoint instead of the scripted provider, set `MENTORLOOP_LLM_PROVIDER=openai`, `MENTORLOOP_LLM_BASE_URL`, `MENTORLOOP_LLM_MODEL` and `MENTORLOOP_LLM_API_KEY` on the app service. That path is untested beyond WireMock.

A demo run (IDs and timestamps change each time):

```
== instructor builds a two-concept course (queues needs stacks)
  course e79deac4-90fd-40b7-a5ba-baa8ece40949 published with 2 lessons and 6 items
== the scripted model drafts items; drafts stay hidden until approved
  ok   learner cannot see a draft
  ok   approved draft goes live
== learner path: lesson first, then practice; queues locked until stacks is mastered
  ok   queues starts locked
  ok   next is the stacks lesson
  ok   then practice
  ok   wrong answer graded
  explanation: A stack pops the element pushed most recently.
  ok   retry with the same clientAttemptId replays seq
  ok   same id, different answer
== grounded tutor (scripted provider: extractive answer from retrieved lesson chunks)
  ok   answer is grounded
  answer: A stack keeps items in last in, first out order [1]. A queue keeps items in first in, first out order [2]. Most recent edit first is stack order [3].
  citations: [{"n":1,"label":"Stacks › What a stack is"},{"n":2,"label":"Queues › What a queue is"},{"n":3,"label":"Worked example: Which data structure backs an editor undo history?"}]
  ok   off-topic question refused
== quiz: while it is open the tutor only gives hints
  ok   tutor switches to hints
  quiz score: 1.0
  ok   another learner's quiz is not visible
== mastery (Bayesian knowledge tracing, applied by the Kafka consumer)
  stacks: MASTERED pKnown=0.974 attempts=4
  queues: UNLOCKED pKnown=0.2 attempts=0
== a borderline short answer waits for the instructor
  ok   outcome
  ok   other learner cannot grade
  ok   graded twice
== certificate once every concept is mastered
  certificate ML-4S23-V5VM-LLVR
  ok   anyone can verify it
  ok   certificate mail with attachment in Mailpit
== spaced repetition: demo clock has 20-second days, so reviews fall due quickly
  reviews due: 3; next: REVIEW
  ok   review reminder mail arrived
== instructor views
  ok   learner cannot open the dashboard
demo passed
```

The question was "What order does a stack keep items in?". The answer also quotes the Queues lesson, because that sentence shares most of its words with the question. That's the scripted provider extracting, not reasoning, on top of lexical retrieval. A real model would probably drop it, but nothing here shows that.

Tests: `mvn verify` runs:
- 49 unit tests, including 7 ArchUnit rules (BKT, SM-2, the recommender, graders, Rasch, the concept graph, retrieval parts, citations and the guard, and both LLM providers, the OpenAI-compatible one against WireMock)
- 20 integration tests on Testcontainers (pgvector Postgres, Redis, Kafka, Mailpit, mock OAuth2): authoring, attempts, learning flow, replay, reviews and the tutor

The concurrency and idempotency checks among them:
- 16 racing retries of one attempt store one row and are graded once
- 24 concurrent distinct attempts by one learner get sequence numbers 1 to 24 with no gaps, and all 24 reach the mastery model
- the attempt events of a learner, replayed reversed, duplicated and with an extra copy, give the same mastery and review cards as the first delivery
- a manual grade that arrives before the attempt it grades still counts once
- 8 concurrent approvals of a draft: one 200, seven 409
- 6 concurrent calibration runs make 6 consecutive difficulty versions

## Numbers

All of these come from one 8 vCPU box shared with other workloads, on 2026-10-31 (IST). The raw output is in `docs/bench/`. Treat them as rough.

**Mastery model, offline** (`MasteryBench`, 20,000 simulated learners each, seed 7). It asks how many answers BKT needs before it says "mastered", and whether the learner really had learned it by then. The simulator uses the BKT model itself, so the first row is the best case by construction. The other two rows show what happens when real learners don't match the configured parameters.

| simulated learners | answers to mastery p50 / p90 | actually learned when flagged |
|---|---|---|
| same parameters as configured | 7 / 16 | 97.8% |
| learn slower (pLearn 0.05) | 12 / 39 | 91.2% |
| guess more, slip more (0.35 / 0.15) | 6 / 15 | 89.6% |

With the wrong parameters, roughly one flagged learner in ten hadn't learned the concept. That's why calibration of the BKT parameters per concept (not built) would matter more than tuning the threshold.

**Rasch calibration, offline** (`CalibrationBench`, simulated responses, seed 11):
- 2,000 learners × 20 answers over 60 items (40,000 responses): 34 iterations, about 64 ms (best of 5). Recovered difficulties correlate r = 0.996 with the true ones, RMSE 0.103 logits.
- 300 learners × 20 answers (6,000 responses): 35 iterations, 13.5 ms, r = 0.970, RMSE 0.241. With fewer responses per item, the estimates are noticeably rougher.

**Compose stack** (`bench.sh`): 20 learners answer 25 items each, all at once, from a bash+curl client on the same box, then 20 learners ask the tutor 2 questions each. Six back-to-back runs:
- every run: 500/500 attempts and 40/40 tutor questions returned 200
- attempts: 323 to 469 per second; latency p50 11.7 to 34.6 ms, p95 30.6 to 74.7 ms. The first run after a restart is the slowest.
- "all applied" (time from the last answer returning until the consumer had applied every attempt): 553 to 1096 ms. This includes the outbox relay's 200 ms poll and the client's progress polling.
- tutor latency p50 12 to 28 ms. This is retrieval plus the guard, with the scripted provider, so it says nothing about real model latency.

The consumer first ran with one thread for the three partitions. With that, "all applied" was 1041 to 1588 ms over three runs (`docs/bench/compose-1-consumer.txt`). Moving to one thread per partition (events are keyed by learner, so each learner's events stay in order on one thread) brought it to 553 to 1096 ms. That's a small sample on a noisy box, so I'd call it "probably better", not a measured speedup. The client is a bash loop of curls, which limits the load more than the server does. These runs don't find the service's limit.

## Limitations

- **Not built:** a code runner for programming exercises, discussion threads, streaming tutor replies (the tutor returns JSON, no SSE), and per-concept fitting of the BKT parameters.
- **Retrieval is lexical.** The hashing embedding is deterministic and free, but it doesn't know synonyms. A real embedding model would plug in behind the same index. The demo shows the scripted provider quoting a neighbouring lesson.
- **The tutor guard is string matching.** It catches the correct option's text, accepted answers and numeric values. A reply that says "it's option B", or paraphrases the answer, gets through.
- **Multi-concept items** update each concept independently, which overstates evidence when an item tests several concepts at once.
- **Late manual grades are applied when they arrive,** after attempts the learner made later, so a regrade doesn't replay BKT from that point.
- **Ordering assumes one relay.** The outbox relay is single-instance. Two relays could publish a learner's events out of order. The consumer would still apply them in order, but with more waiting.
- **The due-review cache can be stale** for up to its TTL after a review is answered.
- **Mail is at-least-once.** A crash between sending a certificate mail and recording it can send it twice. Reminder batches are capped at 200 per run and roll back as a unit.
- **The dashboard has no tutor usage numbers,** only enrolment and per-item attempts.
- **403 vs 404:** another learner's quiz is a 404 (not revealing it exists), while a learner calling an instructor endpoint gets a 403. That's a choice, and it isn't applied uniformly to every resource.
- **Auth is a demo setup.** It uses a mock OAuth2 server, the token subject is the learner, and instructors come from configuration.
- **The benches simulate learners.** They test the models against their own assumptions plus some misspecification, not against real learning data.

## CI

GitHub Actions runs two jobs:
- **test:** `mvn verify` (all tests, Testcontainers on the runner), plus a short run of each offline bench.
- **image:** builds the image, scans it with Trivy (HIGH/CRITICAL, unfixed ignored, two documented ignores in `.trivyignore`), then brings up the compose stack and runs `demo.sh` and `bench.sh` against it.

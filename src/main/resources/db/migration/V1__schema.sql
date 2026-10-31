create extension if not exists vector;

-- ---------- course: authoring ----------
create table course (
    id          uuid primary key,
    title       text not null,
    owner       text not null,
    published   boolean not null default false,
    created_at  timestamptz not null
);

create table concept (
    id          uuid primary key,
    course_id   uuid not null references course (id),
    key         text not null,
    name        text not null,
    pos         int  not null,
    unique (course_id, key)
);

create table concept_edge (
    course_id   uuid not null references course (id),
    prereq_id   uuid not null references concept (id),
    concept_id  uuid not null references concept (id),
    primary key (prereq_id, concept_id)
);

create table module (
    id          uuid primary key,
    course_id   uuid not null references course (id),
    title       text not null,
    pos         int  not null
);

create table lesson (
    id          uuid primary key,
    course_id   uuid not null references course (id),
    module_id   uuid not null references module (id),
    concept_id  uuid not null references concept (id),
    title       text not null,
    body        text not null,
    video_url   text,
    pos         int  not null,
    created_at  timestamptz not null
);
create index lesson_course on lesson (course_id, pos);

create table enrollment (
    course_id    uuid not null references course (id),
    learner      text not null,
    email        text not null,
    cohort       text,
    enrolled_at  timestamptz not null,
    next_seq     bigint not null default 1,   -- per-learner attempt sequence; the row is the per-learner lock
    primary key (course_id, learner)
);

-- ---------- assessment ----------
create table item (
    id            uuid primary key,
    course_id     uuid not null references course (id),
    concept_ids   uuid[] not null,
    type          text not null,
    stem          text not null,
    options       jsonb not null default '[]',
    explanation   text,
    status        text not null,
    origin        text not null,              -- AUTHORED | GENERATED
    source_lesson uuid references lesson (id),
    difficulty    double precision not null default 0,
    difficulty_v  int not null default 0,
    created_by    text not null,
    created_at    timestamptz not null,
    reviewed_by   text,
    reviewed_at   timestamptz
);
create index item_course_status on item (course_id, status);

-- Kept apart from item so no query that feeds the tutor can select it by accident.
create table item_key (
    item_id  uuid primary key references item (id),
    key      jsonb not null
);

create table item_calibration (
    item_id     uuid not null references item (id),
    version     int  not null,
    difficulty  double precision not null,
    responses   int  not null,
    learners    int  not null,
    fitted_at   timestamptz not null,
    primary key (item_id, version)
);

create table quiz (
    id            uuid primary key,
    course_id     uuid not null references course (id),
    learner       text not null,
    item_ids      uuid[] not null,
    status        text not null,              -- OPEN | SUBMITTED | EXPIRED
    opened_at     timestamptz not null,
    closes_at     timestamptz not null,
    submitted_at  timestamptz,
    score         double precision
);
create unique index quiz_one_open on quiz (course_id, learner) where status = 'OPEN';

create table attempt (
    id                 uuid primary key,
    course_id          uuid not null references course (id),
    learner            text not null,
    item_id            uuid not null references item (id),
    client_attempt_id  text not null,
    request_hash       text not null,
    seq                bigint not null,
    quiz_id            uuid references quiz (id),
    answer             jsonb not null,
    latency_ms         bigint not null,
    outcome            text not null,          -- GRADED | NEEDS_REVIEW
    correct            boolean not null,
    score              double precision not null,
    created_at         timestamptz not null,
    graded_at          timestamptz,
    graded_by          text,
    unique (learner, item_id, client_attempt_id),
    unique (course_id, learner, seq)
);
create index attempt_item on attempt (item_id) where outcome = 'GRADED';
create index attempt_review on attempt (course_id, created_at) where outcome = 'NEEDS_REVIEW';

-- ---------- adaptive: mastery, reviews, progress ----------
-- Copy of each attempt as the adaptive side heard it; applied strictly in seq order per learner.
create table practice_event (
    attempt_id  uuid primary key,
    course_id   uuid not null,
    learner     text not null,
    seq         bigint not null,
    item_id     uuid not null,
    concept_ids uuid[] not null,
    outcome     text not null,
    correct     boolean not null,
    latency_ms  bigint not null,
    at          timestamptz not null,
    applied_at  timestamptz,
    unique (course_id, learner, seq)
);

create table learner_progress (
    course_id    uuid not null,
    learner      text not null,
    applied_seq  bigint not null default 0,
    primary key (course_id, learner)
);

create table mastery (
    course_id   uuid not null,
    learner     text not null,
    concept_id  uuid not null,
    p_known     double precision not null,
    attempts    int not null,
    correct     int not null,
    mastered_at timestamptz,
    updated_at  timestamptz not null,
    primary key (course_id, learner, concept_id)
);

create table review_card (
    course_id      uuid not null,
    learner        text not null,
    item_id        uuid not null,
    repetitions    int not null,
    interval_days  int not null,
    ease           double precision not null,
    lapses         int not null,
    due_at         timestamptz not null,
    updated_at     timestamptz not null,
    primary key (course_id, learner, item_id)
);
create index review_due on review_card (course_id, learner, due_at);

create table lesson_read (
    course_id  uuid not null,
    learner    text not null,
    lesson_id  uuid not null,
    read_at    timestamptz not null,
    primary key (course_id, learner, lesson_id)
);

create table certificate (
    code       text primary key,
    course_id  uuid not null,
    learner    text not null,
    title      text not null,
    issued_at  timestamptz not null,
    unique (course_id, learner)
);

create table reminder_sent (
    course_id  uuid not null,
    learner    text not null,
    slot       bigint not null,
    sent_at    timestamptz not null,
    primary key (course_id, learner, slot)
);

-- ---------- tutor ----------
create table chunk (
    id          uuid primary key,
    course_id   uuid not null,
    lesson_id   uuid,
    item_id     uuid,          -- set for worked examples (item explanations); hidden during a quiz
    label       text not null,
    ord         int  not null,
    text        text not null,
    embedding   vector(384) not null,
    tsv         tsvector generated always as (to_tsvector('english', text)) stored,
    check ((lesson_id is null) <> (item_id is null))
);
create index chunk_course on chunk (course_id);
create index chunk_tsv on chunk using gin (tsv);
create index chunk_embedding on chunk using hnsw (embedding vector_cosine_ops);

create table tutor_message (
    id          bigserial primary key,
    course_id   uuid not null,
    learner     text not null,
    question    text not null,
    answer      text not null,
    mode        text not null,          -- ANSWER | HINT
    status      text not null,          -- GROUNDED | DEGRADED_SEARCH | REFUSED | GUARD_BLOCKED
    citations   jsonb not null,
    coverage    double precision not null,
    model       text not null,
    latency_ms  bigint not null,
    created_at  timestamptz not null
);
create index tutor_message_age on tutor_message (created_at);
create index tutor_message_course on tutor_message (course_id, created_at);

-- ---------- outbox ----------
create table outbox (
    id            bigserial primary key,
    event_id      uuid not null unique,
    topic         text not null,
    msg_key       text not null,
    type          text not null,
    payload       jsonb not null,
    created_at    timestamptz not null,
    published_at  timestamptz
);
create index outbox_unpublished on outbox (id) where published_at is null;

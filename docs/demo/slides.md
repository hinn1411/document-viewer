---
marp: true
paginate: true
title: Unified Document Viewer
style: |
  section { font-size: 28px; }
  section.compact { font-size: 22px; }
  section.compact table { font-size: 20px; }
  table { font-size: 24px; }
  img { display: block; margin: 0 auto; }
---

# Unified Document Viewer

**Hien Giang**, Backend Developer

Keyloop Technical Assessment
Scenario D (Domain: Operate) · Backend

<!--
Before recording: put the next two lines in your own words, and recheck the test and mistake counts on slides 8 and 10.

Hi, I'm Hien Giang, a backend developer. [Add one sentence about your background.] I chose Scenario D, the Unified Document Viewer, because I've worked on production platforms that demand resilient design, and this scenario is really about what happens when systems you don't control fail. I built the backend. I'll cover the design, how I worked with AI, and a demo.
-->

---

# The problem

**One VIN → two systems → one list of documents**

- Sales and Service return **different shapes**
- The backend calls them **in parallel**
- Each document shows its **source system**
- Backend built; Swagger stands in for the UI

**Targets:** usually under 1 s · at most about 2.5 s · 99.9% success

<!--
A dealership user enters a VIN and sees every document for that vehicle in one list, with the source on each one. The documents live in two systems that return different shapes, and the backend calls them in parallel. My targets are most searches under a second, no wait over about 2.5 seconds, and 99.9 percent success.
-->

---

<!-- _class: compact -->

# Core assumptions

### Serving stale documents shaped the whole architecture

| Assumption | What it forced |
|---|---|
| **A8: a copy up to 24 h old is acceptable, only if labelled** | **PostgreSQL snapshots · a save after each successful call · a cleanup job · an API that says `STALE`, `partial` and `dataAsOf` · a breaker that can skip a source without losing the answer** |
| A10: VINs can be personal data, so every access is audited | The audit fails closed: no audit, no documents. VINs are masked everywhere |
| A4: the sources return different shapes | One adapter per source, and one common document |
| A1: a gateway passes caller identity | No login here, but a missing identity is still a 401 |

<!--
The brief leaves a lot open, so I wrote down my assumptions. The one that shaped everything is A8: a copy up to 24 hours old is acceptable, but only if it's labelled. Without it, this is an aggregator that stores no documents. With it, I need snapshots in PostgreSQL, a save after every successful call, a cleanup job, and an API that says when data is stale. It's also what lets a breaker skip a source without losing the answer. This is my assumption, and a real dealership would need to confirm it.
-->

---

# System Architecture

![w:1040](architecture.svg)

<!--
The service is stateless, so it scales by adding instances. The API layer checks the client's identity and the VIN. The Search Service calls one adapter per source in parallel, merges the results and audits the access. Each adapter hides its source's shape, so a third source is one new adapter. The database holds the audit log and the fallback copies, and the sources stay the systems of record.
-->

---

# A source failing is normal

| What happens | What the service does | Client gets |
|---|---|---|
| One source slow or down | Serves its copy under 24 h old, if there is one | **200**, `partial: true` |
| Breaker open | Makes no call, then the same as above | **200**, `CIRCUIT_OPEN` |
| No source has anything to serve | Records the failed access | **503** |
| The audit can't be saved | Returns no documents | **503** |
| Invalid VIN or missing identity | Rejects before calling anything | **400** / **401** |

<!--
Optional if you're short of time: one sentence is enough here.

I treat a source failing as a normal case. One slow or broken source can't hide the other's documents. The client gets a 200 with partial set to true, and a status for each source. There are only two 503s: nothing to serve, or an access we couldn't audit.
-->

---

# Decision: a breaker per source

**Why not one breaker on the endpoint?** (ADR 0001)

- It sees **200s** during a partial failure, so it **never opens**
- If it did open, it would **block fallback copies**
- A request it rejects **skips the audit**

**The cost:** more state to tune, and each instance counts failures on its own, because shared state would add a network call to every source call

<!--
Optional if you're short of time: one sentence is enough here.

I compared one breaker on the endpoint with one per source, and wrote it up as an ADR. The endpoint returns 200 during a partial failure, so its breaker can't see the main case. So each source gets its own, and the cost is that each instance counts failures alone.
-->

---

<!-- _class: compact -->

# Implementation highlights

| # | Highlight | Why |
|---|---|---|
| 1 | Two separate limits: HTTP timeouts (300 ms connect, 1.5 s per read) and a 2 s deadline per source | Each covers the other's gap. The timeout ends a stalled call, which the deadline can't. The deadline caps the user's wait, which the timeout can't |
| 2 | Virtual threads, one per source call | Parallel blocking calls are cheap, and the code stays simple to read and test |
| 3 | A circuit breaker per source, outside the retry | Failures are per source, and one search counts as one call |
| 4 | One retry with 100 ms delay, only on 502, 503, 504 or a refused connection, never a timeout | These failures are often short-lived, and retrying a timeout would use up the deadline |

<!--
Four highlights. First, two separate limits that people often mix up. HTTP timeouts, 300 milliseconds to connect and 1.5 seconds per read, end a stalled call and free its resources, but slow data keeps resetting them. The 2 second deadline caps what the user waits, but it can't stop the call. So I need both. Second, virtual threads, one per source call, so parallel blocking calls are cheap and the code stays simple. Third, the breaker sits outside the retry, so one search counts as one call. Fourth, one retry, only for short-lived failures like a 503, and never a timeout, which would use up the deadline.
-->

---

# Observability and tests

**Find the failing source quickly**
- Metrics per source, on a Grafana dashboard, with 4 alert rules
- JSON logs with masked VINs, and a request ID on every response
- Next: traces in Jaeger (T14)

**150 tests** (66 unit, 84 integration)
- Real PostgreSQL through Testcontainers
- The sources mocked over real HTTP with WireMock

<!--
Optional if you're short of time: one sentence is enough here. Update the tracing line once T14 is done.

The risk is two systems I don't control, so metrics are split per source, and an alert leads to the one at fault. Logs are JSON with masked VINs. For testing, I have 150 tests, and the integration tests use real PostgreSQL and real HTTP.
-->

---

# AI story: my harness

![w:960](harness-loop.svg)

<!--
I used Claude Code as the agent, but I decide what done means. Each task starts with slash frame-task, which agrees a brief with me. Then the agent writes failing tests and has to stop. I review them, and those tests are committed before any code exists, so it can't move the goalposts. Then it goes green, a reviewer agent checks the diff, and it refactors. Every AI mistake goes into a log before the PR opens. On the PR, CI runs lint, the build, and unit and integration tests. Only when those are green does Claude review the PR. Then I review it manually.
-->

---

<!-- _class: compact -->

# AI story: four layers of verification

| Layer | What it caught |
|---|---|
| **Automated tests** | `@Retryable` on the adapter's `fetch` did nothing, with no error. `retriesOnceOn503` saw 1 request instead of 2 (T07) |
| **Me, on the local server** | `circuit_open` showed 0 req/s in Grafana while the breaker was open, though every test passed (T13). Also a `WARN` every minute in the server log, from metrics pushed to a collector we don't have (T15) |
| **`code-reviewer` agent** | `half-open-max-wait: 10s` could keep sending a recovered source back to open at low traffic. A throwaway test confirmed it (T12b) |
| **Claude in CI** | Recommended a Hikari `socketTimeout`, so a stalled query fails and the audit fails closed, instead of hanging (T10) |

**25 mistakes logged** in `docs/ai-log.md`, each with how it was caught and fixed

<!--
I don't trust any single check, so there are four layers, and each one caught something the others missed. Automated tests: the AI put a retry annotation where Spring ignored it, with no error, and the tests saw one request instead of two. Me, running the server: every test passed, but Grafana showed zero for an open breaker, because a counter created on first use has no starting value. The reviewer agent caught a half-open timer that could keep a recovered source locked out, and we proved it with a throwaway test. And Claude in CI recommended a database socket timeout, so a stalled query fails instead of hanging. All of these are in my AI log, 25 so far, with how each was caught and fixed.
-->

---

# Live demo

1. VIN `…1`: one list, source on each document, `partial: false`
2. VIN `…2`: slow source, about 1.5 s, `partial: true`
3. VIN `…4`: both sources down, **503**
4. Stop the Service mock: `STALE` copy with `dataAsOf`
5. Burst of searches: `CIRCUIT_OPEN`, and the Service panel in Grafana
6. Stop PostgreSQL: **503**, and the audit panel

<!--
Before recording: follow sections 1 to 3 of scenarios.md, so the stack is up and Grafana has data.

Let's see it running. This is Swagger, standing in for the UI. First, a healthy VIN. Both sources answer, and we get one list, newest first, with the source on each document. Partial is false.

Next, a VIN where Service is too slow. Notice it comes back in about a second and a half, with the Sales documents, partial true, and Service marked as a timeout. The user isn't stuck waiting.

With both sources down and no saved copies, there's nothing to serve, so it's a 503 with each source's status.

Now I'll stop the Service mock completely, and search for the healthy VIN again. It's still a 200, but Service now says stale, with a data-as-of time. That's the labelled copy from assumption A8.

I'll send a burst of searches, and the breaker opens. Now the reason says circuit open, and it answers in milliseconds, because it makes no call. In Grafana, the Service panel shows the errors, then circuit open, so you can see which source is at fault.

Finally, I'll stop PostgreSQL. Both sources are healthy, but this access can't be audited, so it returns a 503 and no documents. The audit panel shows it, and this is an urgent alert.
-->

---

<!-- _class: compact -->

# What I learned, and the challenges

**What I learned**
- Green tests can still be false: read the test count, and run the real thing
- AI over-builds unless you tell it not to. I asked for simpler tests several times
- Simple code you can explain beats clever code

**Challenges**
- **The AI guessed wrong Boot 4 APIs.** Solved by making it look them up through the **Context7 MCP server**
- **The AI created circular dependencies in the architecture.** Adapters imported from `search.model` while `search` called the adapters. Solved by a written one-way dependency rule

<!--
My main lesson is that green doesn't mean correct, so I always run the real thing. AI also over-builds, so I kept pushing for code I can explain. Two challenges: it guessed Spring Boot 4 APIs wrongly, which I solved by making it use the Context7 MCP server, and it created circular dependencies, which I solved with a written one-way rule.
-->

---

# Thank you

**Repository:** github.com/hinn1411/document-viewer

- Design: `github.com/hinn1411/document-viewer/docs/system-design-document.md`
- Decision: `github.com/hinn1411/document-viewer/docs/adr/0001-circuit-breaker-per-source.md`
- AI mistakes: `github.com/hinn1411/document-viewer/ docs/ai-log.md`

<!--
Thanks for watching. The links are all in the repository.
-->

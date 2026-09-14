# Roadmap

Planned features, with enough detail that a later session can pick any one up cold.
The site and pipeline are for personal use: prefer the small, direct implementation
over the general one, and stop when the feature works for one person.

Done so far from this list:

- **Jump to match** (PR #52). Search results link to `/episode/{id}?q=…`; the episode
  page highlights the cues the query matches and steps between them. The query parser
  in `web/.../models/SearchModels.kt` (`searchTerms`, `highlightMatches`) is the reference
  for how the web module reads FTS5 syntax.
- **P5, prefetch the next download**.
  `PodcastPipeline.processPodcast` now decides first (skip rules, consecutive-transcribed
  break) and then `transcribeAll` downloads one episode ahead on a single-thread executor
  while the current one decodes. The orphan path stays sequential.
- **P6, per-podcast language.** `PodcastConfig.language` falling back to a top-level
  `language`, defaulting to `en`. `Transcriber.transcribe(audioPath, language)`.
- **P1, transcript quality gate.** `TranscriptQuality.kt` scores every decode against
  the README's three failure modes plus word confidence, writes the numbers into
  `transcript.json` as `episode_quality`, and re-decodes a flagged episode once, keeping
  the better result. `WhisperTranscription` now carries the `cues` it rendered the VTT
  from. `--no-quality-retry` / `quality_retry: false` turns the retry off.
- **P4, targeted re-transcription.** `--retranscribe`, `--retranscribe-id`,
  `--retranscribe-flagged`, `--retranscribe-limit`. Selection is in `Retranscribe.kt`;
  `PodcastPipeline.retranscribe` decodes and rewrites, keeping every field but the
  transcript, its score, and a recovered episode's duration.
  `writeTranscriptArtifacts` gained `replace`, which stages and atomically moves.
- **W1, tag pills.** Pills on result cards add their tag to the filters; the sidebar
  lists only the active tags, each removing itself. `appendableSearchUrl` is what lets a
  template append one more parameter to a search URL.
- **W2, sort and year.** `SortOrder` (relevance/newest/oldest, with relevance meaning
  newest when there is no query) and a multi-valued `year` over
  `substr(episode_published_on, 1, 4)`. `FilterOptions.years` feeds the sidebar group.
- **W3, shareable timestamped links.** `seekAudio` writes `#t=` with `replaceState`; the
  fragment is honoured on load and outranks the scroll to the first `?q=` match. The
  `timeupdate` handler ignores events raised at `readyState 0`, which is what arming a
  media fragment produces.
- **W4, podcasts page.** `/podcasts` over `EpisodeRepository.getPodcastSummaries()`,
  cached like the filter options. First use of `podcast_image`.
- **W6, word timings.** Optional `app.data.directory` enables `/episode/{id}/words`,
  which serves `words.jsonl.gz` with `Content-Encoding: gzip`. `TranscriptLine.cueIndex`
  counts every cue, blank ones included, which is what joins a word's `seg` to its line.
  Two rules in there took five review rounds to settle and are worth not relitigating:
  the containment check is on the *sidecar* path, since the audio path sits a level below
  it and a value resolving to the root itself would escape; and directory symlinks under
  the root are followed (a library spread across disks) while the sidecar leaf is not,
  opened `NOFOLLOW` as well as checked so the two syscalls cannot be raced. The page
  never lets the sidecar rewrite the transcript: a line is split only when its words
  rebuild it exactly, because the pipeline drops a word whose timings whisper omitted.
- **I1, incremental indexing.** `index.py` stats every `transcript.json` and opens only
  the ones whose mtime or size changed, tracked in `source_path`/`source_mtime`/
  `source_size`/`source_id` on `episodes` plus a `skipped_sources` table for the files it
  walked and deliberately did not index. `episodes_fts` is external-content, so it is
  maintained by hand per row, or rebuilt in one go past `FTS_REBUILD_SHARE` of the
  corpus. `--full` forces the old behaviour and is taken automatically for a database
  without the tracking columns.
- **W7, JSON API.** `quarkus-rest-jackson`, `/api/search` and `/api/episode/{id}`.
  `Episode` gained `snippetText` and `@get:JsonIgnore` on the three getters that should
  not be in a payload.
- **P2, whisper preflight.** `Transcriber.ping()` is asked once in `run()` and
  `retranscribe()` before anything else happens, on its own short timeouts rather than
  the ninety minutes the decode client allows. A decode that could not reach whisper
  increments `decodesUnreachable`, which only makes the run exit non-zero at the end --
  so a run that downloaded the backlog and transcribed none of it cannot look
  successful. The line drawn is **reachability, not correctness**, and it is the one
  thing here worth not relitigating: a status the server chose is the server talking,
  and what it is talking about is that request (whisper.cpp answers 400 "failed to read
  audio data" for an mp3 it cannot decode, 500 for one it cannot process). So only a
  connection that goes nowhere or dies mid-response (the body read is inside the guard,
  not just `execute()`), a gateway `502`/`503`/`504`, or an empty body count, and by the
  same rule the preflight accepts any answer including a 404 (`--request-path` moves
  whisper.cpp's page off `/`).
  The circuit breaker the original entry called for -- a consecutive-failure count that
  stopped the run early -- was built and then cut. Pipeline and whisper run on the same
  machine, so an unreachable server fails instantly rather than after a timeout, and the
  downloads it would have saved are reused by the next run. It cost four guards across
  four loops and was the source of every review finding, one of which would have let
  three undecodable mp3s wedge the pipeline permanently. Don't rebuild it without a
  mid-run failure that actually hurt.

Explicitly declined:

- Replacing that parser with a per-request in-memory FTS5 table so highlighting has the
  database's exact semantics. Correct, but a second connection and per-request table
  building for a gap that only shows on hand-typed FTS5 operators. Not worth it here.
- **W5, transcript export** (VTT/SRT/TXT downloads). Harmless, but not wanted: the owner
  would not use it. `/api/episode/{id}` returns the stored VTT, which covers the one case
  that mattered. Note that SRT would still need cue *end* times, which `parseTranscript`
  discards -- its regex captures only the start.

Filed as issues rather than carried here, because each needs a judgement or a
measurement this document cannot make for you:

- **#67** — let a podcast supply its own `initial_prompt`, so a non-English feed keeps
  the punctuation lever it currently gives up. Real the first time a non-English feed is
  added; the corpus is entirely English today.
- **#68** — rank quality flags by severity instead of counting them. Wants corpus data
  from `episode_quality.flags` before the ordering is chosen.
- **#69** — `--retranscribe-limit` always takes the same prefix, so a permanently flagged
  tail starves everything behind it.
- **#70** — no way to force a re-transcription past the quality comparison, which loses
  a deliberate redo (a language fix, say) to a tie-break that knows nothing about why you
  asked.
- **#71** — two quality reports scored with different signals available are compared as
  if they were alike, so a decode that could not trip `low-confidence` beats one that
  did.
- **#75** — the transcript highlights are unreadable in dark mode. Predates W6 and lives
  in `styles.css`, which is almost entirely hardcoded light hex values; worth one pass
  over the whole file in both themes rather than fixing them one at a time.

## Working conventions

These apply to every item below.

- Branch from `main` as `baz8080/<short-description>`. Never commit to `main`.
- `./gradlew ktlintFormat && ./gradlew test` before every commit. CI (`.github/workflows/ci.yml`)
  runs `ktlintCheck`, `test`, and a smoke test of `index.py` against a two-episode fixture.
- Kotlin, JVM 21. Thymeleaf is a plain library in `web` (see `TemplateEngineProducer.kt`), so
  templates cannot call Kotlin top-level functions; precompute in `SearchResource` and pass
  through `Context.setVariable`.
- Tests construct things by hand: `SearchResourceTest` sets the `lateinit` fields of a
  `SearchResource` directly and mocks `TemplateEngine` with MockK; `EpisodeRepositoryTest`
  builds a real SQLite file in a temp dir with the schema from `index.py`; pipeline tests use
  `pipeline/src/test/.../TestFakes.kt` (`buildPipeline`, `FakeTranscriber`, `FakeFeedService`,
  `makeFeed`, `makeEntry`, `whisperJson`).
- Adding a field to `Episode` means updating the `minimalEpisode`/`episode` helpers in
  `SearchResourceTest` and `SearchModelsTest`. `SearchResourceTest` now calls `search()`
  through a local helper with named defaults, so a new parameter means editing that helper
  rather than every call site.
- `SearchFilters` changes ripple to `buildSearchUrl`, `hasActiveFilters`, the `search()`
  and `apiSearch()` parameters, the `search()` test helper, and `activeFilterCount` in
  `search.html`.
- `Episode` is serialised by Jackson for `/api/*` as well as read by Thymeleaf, so a new
  computed getter lands in the JSON payload unless it carries `@get:JsonIgnore`.
- An **optional** config property must be `Optional<String>`. `@ConfigProperty(name = …,
  defaultValue = "")` does not give an empty string: SmallRye reads an empty default as
  no value and fails validation at RUNTIME_INIT, so the server will not boot at all
  without the setting. `ApplicationStartupTest` exists to catch that and is the only test
  that starts the container.
- `runCatching` catches `Throwable`, not `Exception`. In a request path that turns an
  `OutOfMemoryError` into a 404 with nothing in the log; use `try/catch (e: IOException)`.
- Not every test builds things by hand any more. `WordsRouteHttpTest` is `@QuarkusTest`
  plus `@TestProfile` and REST Assured, for the things that only exist above the resource
  method — `@Context` injection, response headers, conditional requests. Two things to
  know before adding another: starting a Quarkus app in this module unregisters the
  SQLite driver for the plain JDBC tests, which is why `EpisodeRepositoryTest` calls
  `Class.forName("org.sqlite.JDBC")` (any new plain-JDBC test needs the same); and
  `QuarkusTestProfile.getConfigOverrides()` is called more than once, so build the
  fixture once and delete it on exit rather than per call.

### Smoke-testing the web module end to end

Unit tests mock the template engine, so template errors only show when a page renders.
This recipe takes about a minute:

```bash
S=/tmp/smoke; D=$S/data/Show/2024-01-02-abcd1234-hello-there; mkdir -p "$D"; touch "$D/audio.mp3"
python3 - "$D/transcript.json" <<'EOF'
import json, sys
vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nHello there & welcome.\n\n00:00:02.000 --> 00:00:04.000\nNothing to see here.\n"
json.dump({"_id":"abcd1234","podcast_title":"Show","episode_title":"Hello There","episode_published_on":"2024-01-02",
           "episode_transcript":vtt,"episode_relative_audio_path":"Show/2024-01-02-abcd1234-hello-there/audio.mp3",
           "episode_duration":1000,"all_tags":["talk"],"podcast_collections":["c1"]}, open(sys.argv[1],"w"))
EOF
python3 index.py $S/data --db $S/podcasts.db
./gradlew :web:build -x test
APP_DB_PATH=$S/podcasts.db APP_AUDIO_BASE_URL=http://audio.test \
  java -Dquarkus.http.port=18080 -jar web/build/quarkus-app/quarkus-run.jar &
curl -s 'http://localhost:18080/search?q=hello' | grep -o 'href="/episode/[^"]*"'
curl -s 'http://localhost:18080/episode/abcd1234?q=hello' | grep -c '<mark>'
```

Thymeleaf comments inside a `th:each` loop are emitted once per iteration; use the
parser-level form `<!--/* ... */-->` so they are stripped.

---

## Web

Everything listed here has shipped; see "Done so far" above.

---

## Pipeline

Remaining, in suggested order: P3, P8, P7.

### P3. Dry run

**Why.** Global exclusions, per-podcast excludes, the duration floor, `skip_after_consecutive`
and orphan recovery interact. Seeing what a run *would* do before spending GPU hours on it
is worth a flag.

**Where.** `Args`, `AppConfig`, `PodcastPipeline`.

**Design.**

- `--dry-run`: walk feeds and apply every filter exactly as now, but at the point where the
  pipeline would download, log `INFO would transcribe <podcast>/<dir>` and count instead.
  In the orphan scan, list each candidate directory instead of decoding it.
- Nothing may be created: `createPath` makes the podcast and episode directories, so in dry
  run resolve the path without creating (`findExistingEpisodeDir` or a plain `resolve`).
- Finish with a per-podcast and total summary line, and exit 0.

**Tests.** Run with `dryRun = true` over a feed of new episodes: `FakeFeedService.downloads`
empty, `FakeTranscriber.calls` empty, no directories under the data dir afterwards.

**Effort.** Small.

### P8. Run summary file and notification

**Why.** The run tally counts warnings and errors; nothing records what was actually done.
A JSON summary per run feeds the stats page (W4) and a notification.

**Where.** `Logging.kt` (`RunTally`), `PodcastPipeline`, `Main`, `AppConfig`.

**Design.**

- A `RunReport` accumulator in `PodcastPipeline`: per podcast, counts of transcribed,
  recovered, skipped by `SkipReason`, failed, plus start and end times. Serialise with the
  existing `jsonMapper` to `<data-dir>/logs/run-<yyyyMMdd-HHmmss>.json` and copy to
  `logs/latest-run.json`.
- `notify_url` in `pods.yaml`: POST the tally line (the string `RunTally.summary` returns)
  as `text/plain`. That is exactly what ntfy.sh expects; other services can adapt. No auth,
  no retries; a failure to notify is a WARN.
- W4 can show `latest-run.json` when the web module has a data directory (W6's config).

**Tests.** Report contents after a mixed run in `PodcastPipelineRunTest`; notification via
an `open fun notify(url, body)` overridden in a fake.

**Effort.** Small.

### P7. Episode lock for two instances on one data directory

**Why.** The README says two instances must not share feeds because both would stage the
same `audio.mp3.part` and one would delete the other's. A lock per episode directory lifts
that restriction.

**Where.** `PodcastPipeline.transcribeAll` and `recoverEpisode`, README section
"Running two instances at once".

**Design.** `Files.createFile(episodeDir.resolve(".transcribing"))` is atomic and fails if
the file exists; write pid and timestamp into it. If it exists and is younger than six
hours, skip the episode with a debug line; older is stale (a crashed run) and is taken
over. The existing "transcribed by something else" check in `writeTranscriptArtifacts`
stays as the last line of defence.

Since P5 the download no longer sits next to the decode: `transcribeAll` submits the
download for episode N+1 to the prefetch thread before decoding N. Take the lock on the
main thread at submit time, so the other instance is excluded from the download as well as
the decode, and release it after the decode or when the pending future is abandoned in the
`finally`. A lock taken inside the submitted task instead would release before the decode
and let both instances decode the same episode.

**Gotchas.** `O_EXCL` semantics hold on local disks and NFSv3+, and on SMB in practice; say
so in the README rather than promising more.

**Tests.** The `onTranscribe` hook in `buildPipeline` already simulates another instance;
add a case with a fresh lock (skipped) and a stale lock (processed).

**Effort.** Small.

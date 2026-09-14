# Roadmap

Every feature this document planned has now shipped or been declined; what is left
lives in the issues linked below. It stays as the record of what was built and why,
and of what was deliberately not.

The site and pipeline are for personal use: prefer the small, direct implementation
over the general one, and stop when the feature works for one person. Read
"Explicitly declined" before adding anything -- most of it was declined on that
principle rather than on difficulty.

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

- **P3, dry run.** `--dry-run` sets `AppConfig.dryRun` (from `Args` only -- `load` forces
  it, so `pods.yaml` cannot turn it on). The decide-then-do split in `processPodcast` is
  what makes this cheap: the decision pass is untouched and only the doing pass branches.
  `resolvePath` is `createPath` without the `createDirectories`, which is the one trap --
  the podcast directory was the only thing a walk created. Whisper is not pinged, so a dry
  run works with the server off, and `--retranscribe` lists its targets rather than
  redoing them.
- **P8, run report and notification.** `RunReport` accumulates per-podcast counts of
  transcribed, recovered, failed and skipped-by-reason, and writes
  `logs/run-<stamp>.json` plus a copy at `logs/latest-run.json`. It builds a plain Map
  rather than serialising itself, which keeps the JSON shape snake_case like
  `transcript.json` without annotating anything. `Notifier` POSTs the tally line as
  text/plain to `notify_url`, which is what ntfy.sh takes. Both are best-effort: a report
  that cannot be written and a notification that fails are warnings, because the run has
  already done its work by then -- and both happen in a `finally`, since a run that died
  is exactly when its summary is worth having. The report gets its own mapper: the
  episode one sorts keys, which would alphabetise it and make the insertion order the
  class relies on inert. Reports are pruned at 14 days like the error log, and a stamped
  name already taken gets a `-2` suffix, because two instances share a data directory and
  the stamp is only to the second. A dry run writes no report at all: a report records
  work done, and one from a dry run would overwrite `latest-run.json` with a record of
  none. The web side of the original entry -- W4 showing `latest-run.json` -- was not
  built; the file is there if it is ever wanted.

- **Feed markup survey.** `--dump-feed-markup <url>` prints what a feed carries per
  entry that the pipeline does not map, plus a tally across the sample. Built to test
  whether feeds expose segment boundaries an ad classifier could use. ROME 2.1.0's
  `rome-modules` registers nothing for the Podcasting 2.0 namespace, so `podcast:chapters`,
  `podcast:soundbite` and any host-specific segment tags arrive in `SyndEntry.foreignMarkup`
  and are currently dropped on the floor -- `episodeFields` in `PodcastPipeline` never
  looks at it. Nothing is mapped off the back of this yet: the survey comes first, because
  `podcast:chapters` is a *link* to a JSON file, not inline data, and whether it marks ads
  varies by host. Reading that file would be a second fetch per episode and belongs in its
  own change.

Explicitly declined:

- Replacing that parser with a per-request in-memory FTS5 table so highlighting has the
  database's exact semantics. Correct, but a second connection and per-request table
  building for a gap that only shows on hand-typed FTS5 operators. Not worth it here.
- **W5, transcript export** (VTT/SRT/TXT downloads). Harmless, but not wanted: the owner
  would not use it. `/api/episode/{id}` returns the stored VTT, which covers the one case
  that mattered. Note that SRT would still need cue *end* times, which `parseTranscript`
  discards -- its regex captures only the start.
- **P7, episode lock for two instances on one data directory.** Two instances with
  different `pods.yaml` files already work: different feeds mean different podcast
  directories and no `audio.mp3.part` collision. The lock only buys two instances sharing
  the *same* feeds, which the README forbids and nobody wants -- the dual-instance setup
  it was written for paid off on an M2 and is not how this runs now. Against that it is
  the riskiest thing left: since P5 the download is submitted to the prefetch thread
  before the current episode decodes, so the lock has to be taken on the main thread at
  submit time and released after the decode or when the pending future is abandoned --
  a lifetime across an async boundary with three exit paths, which a fake transcriber
  cannot really test. A wrong lock is worse than none: too short a stale window and both
  instances decode the same episode, too long and a crashed run blocks an episode for
  hours, and either way it fails silently. The shared-data-directory case that does bite
  -- both instances writing `logs/` -- is already handled by the run report's stamped
  filename suffix. Reopen this only if two instances ever need the same feeds.

Filed as issues rather than carried here, because each needs a judgement or a
measurement this document cannot make for you:

- **#67** — let a podcast supply its own `initial_prompt`, so a non-English feed keeps
  the punctuation lever it currently gives up. Real the first time a non-English feed is
  added; the corpus is entirely English today.
- **#76** — an unrecognised language code decodes in the wrong language instead of
  failing: whisper.cpp looks the code up in a map keyed by lower case and never checks
  the result, so an unmatched one silently selects the wrong language token. Pairs with
  #67; both are `pods.yaml` going unvalidated, and one pass covers them.
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

Everything listed here has shipped; see "Done so far" above.

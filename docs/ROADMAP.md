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

Explicitly declined:

- Replacing that parser with a per-request in-memory FTS5 table so highlighting has the
  database's exact semantics. Correct, but a second connection and per-request table
  building for a gap that only shows on hand-typed FTS5 operators. Not worth it here.

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
  `SearchResourceTest` and `SearchModelsTest`. Adding a parameter to `search()` means updating
  its positional calls in `SearchResourceTest`.
- `SearchFilters` changes ripple to `buildSearchUrl`, `hasActiveFilters`, the `search()`
  parameters, and `activeFilterCount` in `search.html`.

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

Suggested order: W1, W2, W3, W4, W5, W6, W7.

### W1. Clickable tag pills and a tag filter

**Why.** The search endpoint already accepts `tag` and the repository filters on it
(`addCsvContainsFilter(filters.tags, "e.all_tags")`), but nothing in the UI exposes it.
Tag pills on result cards are inert spans, and `activeFilterCount` in `search.html`
ignores `filters.tags`.

**Where.** `web/src/main/resources/templates/search.html`, `templates/episode.html`,
`web/.../SearchResource.kt`, `web/.../models/SearchModels.kt`.

**Design.**

- A pill becomes a link that *adds* its tag to the current filters and resets to page 1,
  swapped in place like every other filter link (`hx-get`, `hx-target="#app"`,
  `hx-swap="outerHTML"`, `hx-push-url="true"`). Build the href in the template as
  `${tagBaseUrl} + '&tag=' + ${#uris.escapeQueryParam(tag)}` where `tagBaseUrl` is
  `buildSearchUrl(filters.copy(page = 1))` set in `SearchResource.search`. `buildSearchUrl`
  returns `/search?` with no params, giving `/search?&tag=x`; harmless, but trim the
  trailing `?` if it offends.
- Do not list every tag in the sidebar: feeds contribute thousands of keywords. Add a
  "Tags" filter group that shows only the *active* tags, each with a remove link
  (`buildSearchUrl(filters.copy(tags = filters.tags - tag, page = 1))`, precomputed as a
  map in the resource). Pills on cards are how tags get added.
- Count tags in `activeFilterCount`.
- On the episode page, render `episode.tagList` as pills linking to `/search?tag=…`
  instead of the comma-joined `allTags` text.

**Gotchas.** Tags are lowercased and trimmed by the pipeline (`normaliseTags`) and matched
case-insensitively with comma delimiters, so a tag can never contain a comma. Spaces
must encode as `%20`, not `+` (see the comment on `urlEncode` in `SearchModels.kt`).

**Tests.** `SearchResourceTest`: `tagBaseUrl` and the remove-link map are set. `SearchModelsTest`:
URL building with tags containing spaces and ampersands (a case already exists for `tag=`).

**Effort.** Small.

### W2. Sort order and year filter

**Why.** With a query, results are BM25 only; there is no way to see the newest match first
or to restrict to a year. `episode_published_on` is `YYYY-MM-DD` text with an index
(`idx_episodes_published`), so lexicographic comparison and `substr(…, 1, 4)` both work.

**Where.** `EpisodeRepository.search` and `getFilterOptions`, `SearchFilters`, `FilterOptions`,
`buildSearchUrl`, `search.html`.

**Design.**

- `sort` parameter: `relevance` (default when there is a query), `newest`, `oldest`. With no
  query, relevance is meaningless: treat it as `newest`. SQL: `ORDER BY episodes_fts.rank`
  for relevance, `ORDER BY e.episode_published_on DESC, episodes_fts.rank` for newest with a
  query (the second key keeps ties stable), and without the FTS join for no-query.
- `year` parameter, multi-valued like `podcast`: `substr(e.episode_published_on, 1, 4) IN (…)`.
  `FilterOptions` gains `years: List<String>` from `SELECT DISTINCT substr(episode_published_on,1,4)`
  filtered by the current query like the other options are, sorted descending. Render as a
  checkbox group in the sidebar, same markup as the Podcast group.
- Sort control: a `<select name="sort">` inside `#filter-form` so `hx-include` picks it up
  from the search input, with `hx-trigger="change from:input[type='checkbox'], change from:select"`
  on the form. Show it only when there is a query, since it does nothing otherwise.
- Recovered orphans keep a real date from the directory name; a null date sorts last under
  `DESC`, which is fine.

**Tests.** `EpisodeRepositoryTest` has fixtures with dates; add cases for each sort and for
the year filter. `SearchModelsTest` for URL round-tripping of `sort` and `year`.

**Effort.** Small to medium.

### W3. Shareable timestamped links

**Why.** Clicking a cue seeks the audio but the URL never changes, so a passage cannot be
pasted anywhere.

**Where.** `templates/episode.html` script block only.

**Design.**

- In `seekAudio(seconds)`, call `history.replaceState(null, '', '#t=' + Math.floor(seconds))`
  so the address bar always holds the last cue clicked. Add a small "link" glyph or title on
  the timestamp to hint at it.
- On load, parse `location.hash` for `t=`. Scroll the last cue whose `data-start-ms` is at
  or below `t * 1000` into view (reuse `findActiveLine`) and mark it `active`.
- Getting the audio to start there: the player is `preload="none"`, and setting
  `currentTime` before metadata loads is unreliable across browsers. The robust option is a
  media fragment: set `audioEl.src = audioEl.src.split('#')[0] + '#t=' + seconds` before the
  first play, which every major browser honours natively. Fall back to a one-shot
  `loadedmetadata` listener that sets `currentTime`.
- Precedence with `?q=`: a `#t=` fragment wins over the scroll-to-first-match on load;
  the match navigator still works afterwards.

**Tests.** None unit-testable; use the smoke recipe and open the page with `#t=2`.

**Effort.** Small.

### W4. Podcasts page with stats

**Why.** `/` redirects to `/search`, which does list the newest episodes, but there is no
overview of what the corpus holds or when it was last indexed. Filtering by podcast already
works through `/search?podcast=…`, so a per-podcast page is just a listing that links there.

**Where.** New `getPodcastSummaries()` in `EpisodeRepository`, new `/podcasts` route in
`SearchResource`, new `templates/podcasts.html`, a nav link on `search.html`.

**Design.**

- Query: `SELECT podcast_title, podcast_image, COUNT(*), SUM(episode_duration),
  MIN(episode_published_on), MAX(episode_published_on) FROM episodes GROUP BY podcast_title
  ORDER BY podcast_title`. Cache it like `getFilterOptions` does (60 s TTL), since it scans
  the table.
- Page: one row or card per podcast with artwork (`podcast_image` is in the schema and
  currently unused), episode count, total hours, date range, linking to
  `/search?podcast=<encoded title>`. A header line with totals and "index built" from
  `Files.getLastModifiedTime(Path.of(dbPath))`, which is when `index.py` last wrote the file.
- Make the podcast name on each result card a link to the same filtered search.

**Gotchas.** `podcast_title` is the join key everywhere; there is no podcast id. Remote
artwork URLs come from feeds; render with `referrerpolicy="no-referrer"` and no sanitising
is needed since they go into `src` through `th:src`, which escapes.

**Tests.** `EpisodeRepositoryTest` for the summary query against the fixtures.
`SearchResourceTest` for the context variables.

**Effort.** Small.

### W5. Transcript export

**Why.** The VTT is in the database but can only be read on the page.

**Where.** `SearchResource` (new routes), `SearchModels.parseTranscript`, `templates/episode.html`.

**Design.**

- Routes `/episode/{id}/transcript.vtt`, `.srt`, `.txt` with `Content-Disposition: attachment`
  and a filename from `escapeFilename`-style slugging of the episode title.
- VTT: the stored string as is, `text/vtt`.
- TXT: cue texts joined with newlines; optionally prefix each with the display timestamp.
- SRT: needs cue *end* times, which `parseTranscript` discards (its regex captures only the
  start). Extend `TranscriptLine` with `endMillis` (default null so existing constructor
  calls and the equality assertions in `SearchModelsTest` keep passing) and capture the
  second timestamp. SRT format: sequence number, `HH:MM:SS,mmm --> HH:MM:SS,mmm` with a comma,
  text, blank line.
- Add a "Download: VTT · SRT · Text" row to the meta table on the episode page.

**Tests.** `SearchModelsTest` for the SRT conversion and end-time parsing.

**Effort.** Small.

### W6. Word timings in the web UI

**Why.** The pipeline writes `words.jsonl.gz` (per-word start, end, probability and cue
index) beside every `audio.mp3`, and nothing reads it. It enables word-level highlighting
during playback and dimming low-confidence runs so the reader knows where whisper guessed.

**Where.** New config `app.data.directory` in `web/src/main/resources/application.properties`
(optional; feature hidden when unset), new route in `SearchResource`, `templates/episode.html`.

**Design.**

- Serving the file. The web module only knows the database and an external audio base URL.
  Two options: (a) fetch `APP_AUDIO_BASE_URL + <episode dir> + /words.jsonl.gz` directly from
  the browser, since it sits beside the audio the same server already serves; needs that
  server to allow CORS and the page to gunzip with `DecompressionStream('gzip')`. (b) Give
  the web module an optional data directory and serve `/episode/{id}/words` by reading the
  file next to `episode_relative_audio_path`, with `Content-Encoding: gzip` so the browser
  inflates it. Prefer (b): no CORS, no new JavaScript decompression, and the path is derived
  from a column the module already has. Guard the path: resolve under the data directory
  and refuse anything that escapes it.
- Joining words to cues. Each word carries `seg`, the index of its segment in the whisper
  response, which is the cue ordinal in the VTT. `parseTranscript` skips cues with blank text,
  so line index is not cue ordinal; add a `cueIndex` to `TranscriptLine` counting every cue,
  and emit it as `data-cue` on each line. Map words to lines by that.
- Rendering. On first play, fetch the words, group by cue, and replace each line's text span
  with one span per word built from the `w` values (whisper words carry their leading space,
  so concatenation reproduces the cue text). On `timeupdate`, binary-search the current word
  and move a `current-word` class. A toggle dims words with `p` below about 0.4.
- Interaction with jump-to-match: when the page has `?q=`, the line text is already marked up
  with `<mark>`. Either skip the per-word rewrite on matched lines or apply the marks at
  word level by re-running the term match per word. The first is acceptable.

**Gotchas.** The file is roughly 60 KB gzipped per episode; fetch lazily, never on page
load. Episodes transcribed before word timestamps were added have no sidecar; the route
returns 404 and the page falls back silently.

**Tests.** `SearchResourceTest` for the route: 404 without config, 404 without file, path
escape refused. `SearchModelsTest` for `cueIndex`.

**Effort.** Medium. Nice rather than necessary; do it last on the web side.

### W7. JSON search endpoint

**Why.** Makes the corpus scriptable from the shell or a notebook.

**Where.** `web/build.gradle.kts`, `SearchResource`.

**Design.** Add `io.quarkus:quarkus-rest-jackson` (the module has `quarkus-rest` only, so no
JSON serialisation today). A `/api/search` route with the same parameters as `/search`,
returning `SearchResult` as JSON, and `/api/episode/{id}` returning the `Episode` with its
transcript. `Episode` has computed getters (`formattedDuration`, `tagList`, `snippetHtml`)
that Jackson will serialise too; annotate with `@JsonIgnore` or accept them.

**Tests.** `SearchResourceTest` can call the method directly and check the returned object.

**Effort.** Small.

---

## Pipeline

Suggested order: P1 and P4 together, then P2, P3, P6, P8, P7. P5 is done.

### P1. Transcript quality gate

**Why.** The README documents three failure modes that were only ever found by external
repair passes: an entire episode decoded with no punctuation, greedy-style repetition
loops, and cues shredded into one- and two-word fragments. The pipeline should score each
transcript as it writes it, record the score, and retry once when it fails.

**Where.** New `pipeline/src/main/kotlin/com/rsstowhisper/pipeline/TranscriptQuality.kt`;
`external/WhisperTranscription.kt`; `PodcastPipeline.transcribeEpisode`,
`buildEpisodeDict`, `buildRecoveredEpisodeDict`; `AppConfig`, `Args`.

**Design.**

- `WhisperTranscription.parse` currently renders the VTT string and drops the segments.
  Keep them: add `cues: List<Cue(start, end, text)>` to the data class so the scorer does not
  re-parse VTT.
- `QualityReport` computed from a `WhisperTranscription`:
  - `punctuationPerWord`: count of `. , ! ? ; :` over word count. Healthy episodes sit
    around 0.15 (README's paired trial); the failure mode is near zero. Flag below 0.03.
  - `secondsPerCue`: total speech span over cue count. Shredded episodes measured 0.74 and
    healthy ones 2.4. Flag below 1.0 when there are at least 50 cues.
  - `repeatedShare`: the share of all words covered by the single most frequent 4-gram, plus
    the longest run of consecutive identical cue texts. Flag when the share exceeds 0.05 or
    the run reaches 4.
  - `meanWordProbability` and the fraction of words with `p < 0.3`. Flag when the fraction
    exceeds 0.2. These come from `words`, which are empty for old responses; skip the check
    then.
  - `flags: List<String>` naming what tripped.
- Write it into `transcript.json` as `episode_quality` with the numbers and the flags, in
  both the feed path and the recovered path (both dict builders are in the companion; pass
  the report in). `index.py` reads known keys only, so an extra key is harmless; indexing
  `flags` later is optional.
- Retry: `transcribeEpisode` becomes the single place that calls the transcriber, parses,
  scores, and on a flagged result decodes once more and keeps the better of the two (fewer
  flags, then higher punctuation ratio). Whisper is not deterministic, and the README's
  repair passes fixed most loops on the first retry. Config `quality_retry: true` in
  `pods.yaml` (`AppConfig.qualityRetry`) and `--no-quality-retry`. Log a WARN when the kept
  result is still flagged, so it reaches the error log and the run tally.
- Both callers (`processPodcast` and `recoverEpisode`) currently call
  `WhisperTranscription.parse(transcribeEpisode(...))`; change `transcribeEpisode` to return
  the parsed, scored result so both get the retry.

**Gotchas.** A retry doubles decode time for the 1 to 5 percent of episodes that trip a
flag; acceptable. Thresholds are starting points from the README's measurements, not
tuned constants; keep them in one place at the top of the file with the numbers they came
from, and expect to adjust after a run over the real corpus.

**Tests.** `TranscriptQualityTest` with synthetic transcriptions: healthy, unpunctuated,
looping (the same cue text 20 times), shredded (200 cues of one word each), low confidence.
`PodcastPipelineRunTest`: `FakeTranscriber` needs to return a sequence (extend `TestFakes`
with `vtts: List<String>` consumed in order); assert a flagged first response triggers exactly
one retry and the good response is written, and that `--no-quality-retry` makes one call.

**Effort.** Medium.

### P4. Targeted re-transcription

**Why.** The only way to redo an episode is deleting its `transcript.json` by hand. With P1
recording flags, the loop closes: find flagged episodes, decode them again.

**Where.** `Args`, `Main`, new `pipeline/.../pipeline/Retranscribe.kt`, `PodcastPipeline.writeTranscriptArtifacts`.

**Design.**

- Flags: `--retranscribe <podcast dir>/<episode dir>` repeatable; `--retranscribe-id <hex8>`
  repeatable (find by walking podcast directories for a name containing `-<hex8>-`);
  `--retranscribe-flagged` (read every `transcript.json`, select those with non-empty
  `episode_quality.flags`; slow on a network volume, say so in `--help`). Any of these puts
  the run in re-transcription mode: no feeds are fetched.
- Per target: require `audio.mp3`; decode through the same scored `transcribeEpisode` from
  P1; then rewrite `transcript.json` keeping every existing field and replacing only
  `episode_transcript`, `episode_quality`, and `episode_duration` when
  `episode_metadata_recovered` is true (that duration came from the previous decode). Write
  `words.jsonl.gz` first, then the JSON to a temp name and `ATOMIC_MOVE` over the old one,
  so a crash never leaves the episode without a transcript.
- `writeTranscriptArtifacts` refuses when `transcript.json` exists; add a `replace: Boolean`
  parameter rather than deleting first.
- Honour `--orphan-limit`-style bounding with `--retranscribe-limit <n>` for the flagged
  mode, since a first run over the corpus could select hundreds.

**Tests.** Create a directory with an existing `transcript.json` and `audio.mp3`, run in
re-transcription mode with a `FakeTranscriber`, assert the transcript changed, the other
fields survived byte for byte, and no feed was requested (`FakeFeedService.requestedUrls`
empty).

**Effort.** Small once P1 exists.

### P2. Whisper preflight and circuit breaker

**Why.** `processPodcast` catches every exception per entry and continues, so a whisper
server that is down causes every remaining episode in every feed to be downloaded and then
fail one at a time. The downloads are not wasted (the next run finds `audio.mp3` and skips
straight to decoding), but the run takes hours to report a failure that was known at the
first episode.

**Where.** `Transcriber`, `PodcastPipeline.run` and `processPodcast`, `AppConfig`.

**Design.**

- Preflight in `run()` before any feed is fetched: one GET to the server's base URL. Any
  2xx is enough (whisper-server answers `/` with a page; do not depend on a `/health` route
  without checking the build in use). On failure log an error and return `false`, which
  `Main` already turns into exit 1.
- Breaker: wrap `transcriber.transcribe` so connection failures and non-2xx responses throw a
  dedicated `TranscriberUnavailable` exception, distinct from an empty transcript. Count
  consecutive occurrences across the whole run in a field like `orphansRecovered`; reset on
  any success. At `max_consecutive_transcriber_errors` (default 3, in `pods.yaml`) log one
  error naming the last failure, stop processing further entries and podcasts, and return
  `false`. Both the feed loop and `recoverAll` must check the tripped state before each decode.

**Tests.** `buildPipeline(transcriberFails = …)` already exists. Assert that with three
entries and a failing transcriber, `FakeTranscriber.calls` stops at the threshold, later
downloads do not happen, and `run()` returns `false`. Preflight is HTTP; give `Transcriber`
an `open fun ping(): Boolean` and override it in `FakeTranscriber`.

**Effort.** Small.

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

### P6. Per-podcast language

**Why.** `Transcriber.transcribe` sends `language=en` unconditionally.

**Where.** `PodcastConfig`, `AppConfig`, `Transcriber`, `TestFakes`.

**Design.** `language` on `PodcastConfig` (nullable) falling back to a top-level `language`
in `pods.yaml` defaulting to `en`. `transcribe(audioPath, language)`; `FakeTranscriber`
overrides the new signature. The web module already stores `podcast_language` from the
feed; the captions `<track srclang="en">` in `episode.html` could use it, but that is
cosmetic.

**Tests.** `TranscriberTest` inspects the multipart body; assert the field. One pipeline
test that the per-podcast value reaches the transcriber.

**Effort.** Tiny.

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

**Where.** `PodcastPipeline` around the download-and-decode block and `recoverEpisode`, README
section "Running two instances at once".

**Design.** Before downloading, `Files.createFile(episodeDir.resolve(".transcribing"))`,
which is atomic and fails if the file exists; write pid and timestamp into it. If it exists
and is younger than six hours, skip the episode with a debug line; older is stale (a crashed
run) and is taken over. Delete in `finally`. The existing "transcribed by something else"
check in `writeTranscriptArtifacts` stays as the last line of defence.

**Gotchas.** `O_EXCL` semantics hold on local disks and NFSv3+, and on SMB in practice; say
so in the README rather than promising more.

**Tests.** The `onTranscribe` hook in `buildPipeline` already simulates another instance;
add a case with a fresh lock (skipped) and a stale lock (processed).

**Effort.** Small.

### P5. Prefetch the next download during decoding

**Why.** Download and decode alternate, so the GPU idles for every download. One-ahead
prefetch bounds disk use and recovers most of the gap.

**Where.** `PodcastPipeline.processPodcast`.

**Design.** Split the loop into deciding and doing. First walk the feed applying the skip
rules and the consecutive-transcribed break, collecting the entries that need work. Then
process them with a single-thread executor: submit the download for item N+1 immediately
before decoding item N, and wait on its future when N finishes. On breaker trip (P2) or
error, cancel the pending future; `downloadAudio` already cleans up `.part` in `finally`.
Keep the orphan path sequential; it is a backlog, not the hot path.

**Gotchas.** The deciding pass must keep the exact `skip_after_consecutive` semantics,
including the reset on any gap, or the orphan scan's "shadowed by threshold" report drifts.
Two instances on one directory (P7) make this safe only with the lock taken at submit time.

**Tests.** With the `onTranscribe` hook, assert that when decoding entry 1 begins,
`FakeFeedService.downloads` already contains entry 2 but not entry 3.

**Effort.** Medium. Measurable but modest gain; last on the list.

---

## Indexer

### I1. Incremental indexing

**Why.** `index.py` drops both tables and re-reads every `transcript.json` on every run.
On the corpus sizes in the README that is minutes of network I/O to add a handful of new
episodes, which discourages running it after every pipeline run.

**Where.** `index.py`, the smoke test in `.github/workflows/ci.yml`.

**Design.**

- Add `source_path TEXT` and `source_mtime REAL` columns. On a run, walk the directories
  as now but only `stat` each `transcript.json`; read and upsert those whose mtime differs
  from the stored value or that are new; delete rows whose file is gone.
- `episodes_fts` is an external-content table, so rows cannot simply be replaced: before
  updating or deleting an episode row, issue the FTS delete command
  (`INSERT INTO episodes_fts(episodes_fts, rowid, episode_title, episode_transcript_plain,
  podcast_title, all_tags) VALUES('delete', old.rowid, …)` with the *old* values), then
  update and insert the new FTS row. Alternatively fall back to the full `'rebuild'` when
  more than, say, 20 percent of rows changed.
- Duplicate-GUID disambiguation (`disambiguate_ids`) needs every id, which today means
  reading every file. The id is the `<hex8>` in the directory name (`md5(guid)[:8]`, the
  same value as `_id`) and the ordering key is the audio path, also derivable from the
  directory name, so the clash groups can be computed from the listing without opening any
  file. Do that, and only read the JSON for rows that changed.
- Keep `--full` for the current behaviour, and take it automatically when the database
  lacks the new columns.

**Gotchas.** The web module holds an open read connection under WAL; incremental writes
are fine, as the full rebuild already is. Keep the "nothing to index leaves the database
untouched" guard.

**Tests.** Extend the CI smoke test: run twice, assert the second run reports zero changes,
touch one fixture and assert exactly one row updated and FTS still finds its new text.

**Effort.** Medium.

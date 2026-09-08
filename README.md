# rss_to_whisper

Transcribe podcast episodes from RSS feeds using the [whisper.cpp](https://github.com/ggml-org/whisper.cpp) HTTP server and index them into a local SQLite FTS5 database for full-text search.

## Modules

### `pipeline` (Kotlin)
Walks one or more RSS feeds, downloads each episode's MP3, and POSTs it to a running whisper.cpp HTTP server. The server decodes and resamples the audio itself, so no local transcoding step is needed and the MP3 is what gets kept on disk. It returns `verbose_json`, from which the pipeline renders a [WebVTT](https://www.w3.org/TR/webvtt1/) transcript into `transcript.json` and writes the per-word timings alongside as `words.jsonl.gz`. Designed to run on a schedule (e.g. cron) to keep transcripts up to date.

### `web` (Kotlin)
A Quarkus HTTP server that serves a full-text search interface over the SQLite database produced by `index.py`. Supports filtering by podcast, collection, episode type, and duration. Search results are ranked by BM25 relevance. The episode detail page shows a clickable transcript synced to the audio player. Opened from a search result it carries the query along (`/episode/{id}?q=…`), highlights every cue the query matches, scrolls to the first, and steps between them with Prev/Next or the `n`/`p` keys. Runs on port 8080 by default.

The FTS index is one row per episode, so SQLite reports *which* episodes match but not *where*. The episode page re-applies the query cue by cue, reading it the way FTS5 does: quoted phrases stay whole, `AND`/`OR`/`NOT`/`NEAR` are operators only in upper case, a trailing `*` is a prefix, and words are compared case-insensitively with diacritics removed, as the unicode61 tokenizer indexed them.

> **Note:** whisper-server also defaults to 8080. They are rarely up at the same time, but if they are, move one — `--port` on whisper-server, `quarkus.http.port` on the web module.

## Python scripts

### `index.py`
Reads all `transcript.json` files in the data directory and writes them into a SQLite FTS5 database. Run this after the pipeline to make new transcripts searchable.

Requires Python 3 with an FTS5-capable SQLite. The script prefers `pysqlite3` when installed and falls back to the standard library `sqlite3` module otherwise, exiting with a clear error if neither has FTS5:

```bash
pip install pysqlite3
```

> **Note:** The default system `sqlite3` on some platforms (e.g. Synology NAS) does not include FTS5. `pysqlite3` bundles a SQLite build that does.


## Prerequisites

- JDK 21+
- A running [whisper.cpp HTTP server](#running-the-whispercpp-server)
- Python 3 with an FTS5-capable SQLite (for the indexing script; `pip install pysqlite3` if the system build lacks FTS5)

No `ffmpeg` is required. MP3s are uploaded as-is and the whisper.cpp server decodes them with its
built-in miniaudio decoder, resampling to 16 kHz mono internally.

## Building

```bash
./gradlew build
```

## Running the whisper.cpp server

The pipeline POSTs audio to the whisper.cpp `/inference` endpoint. It does not
start or manage the server — start it yourself and leave it up for the run:

```bash
whisper-server \
  -m /path/to/models/ggml-large-v3.bin \
  --port 8080
```

The `whisper-server` binary is built alongside `whisper-cli` when you compile
whisper.cpp. Set `PIPELINE_WHISPER_SERVER_URL` in `pipeline/.env` to its base
URL.

**Only the model is chosen at launch.** Everything else that matters is a
per-request form field, and `server.cpp` overrides launch defaults with
whatever a request actually sends. So the pipeline's own settings win, and the
only knob you pick when starting the server is `-m`.

Do not pass `--convert` (it shells out to `ffmpeg` on the server host — MP3s are
uploaded as-is and decoded internally), and do not pass `-nt`, which would
suppress the timestamps the pipeline exists to capture.

### Choosing a model

whisper.cpp uses its own GGML model format (`.bin` files), **not** the OpenAI
Python `.pt` files. Download from HuggingFace:

```bash
curl -L -o ggml-large-v3.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin
```

Put the matching `ggml-large-v3-encoder.mlmodelc` next to the `.bin`, or the
encoder silently falls off the Neural Engine and everything gets much slower.

#### large-v3, not large-v3-turbo

Turbo prunes the **decoder** and keeps the encoder, and under Core ML the
encoder is where most of the wall clock goes. Measured over 13 episodes,
large-v3 costs **1.36x** turbo's decode time — not the 2-3x that "turbo for
speed" implies — and produced a usable transcript on 10 of 13 episodes turbo
could not decode at all.

It also fixes a failure nothing else touches. Turbo shreds some episodes into
runs of one- and two-word cues: 1,256 episodes in one corpus. An A/B on 4
shredded episodes across 4 arms took fragmentation to zero in **all sixteen
cells, including the no-prompt no-VAD control**. That is a model difference,
and neither the prompt nor VAD substitutes for it.

## What the pipeline sends, and why

These are the request fields in `Transcriber.kt`. Each one is load-bearing.

### `prompt` and `carry_initial_prompt` — the single biggest lever

Whisper intermittently decodes an entire episode with **no punctuation and no
capitals**. That is not cosmetic: whisper segments on sentence structure, so
with no full stops the cue boundaries stop tracking speech and every timestamp
derived from them is fiction. 654 episodes were hit, and 13 resisted every
attempt to re-decode them.

An `initial_prompt` of ordinary punctuated prose fixed **all 13**:

| approach | fixed |
|---|---|
| **initial_prompt** | **13 / 13** |
| large-v3 | 10 / 13 |
| best VAD parameter | 9 / 13 |
| Silero VAD v6.2.0 | 6 / 13 |
| re-decode at another VAD threshold | 0 / 13 |

It works because this is a **decoder** mode, not a segmentation problem. VAD
only changes what audio reaches the decoder; a prompt conditions the decoder
itself, and punctuation is a style. It also prevents runaway repetition loops —
without it, 4 of 4 test decodes collapsed, one returning 539 usable words out of
10,660, and ran 2.5x slower because a looping decode burns tokens.

`carry_initial_prompt=true` matters: without it only the first window is
conditioned, and an episode that degrades later still degrades (13/13 with,
12/13 without).

Keep the prompt generic. It biases **vocabulary** as well as style, so anything
domain-specific contaminates transcripts. The default in `Transcriber.kt` was
checked against a repaired episode — zero occurrences of any prompt fragment,
word count within 5% of the original.

### `vad=false` — sent explicitly, and off

Not merely omitted. A request that omits `vad` inherits whatever the server was
launched with, silently, which is how one corpus ended up with no record of its
own VAD state.

It has to be off because VAD breaks word timestamps. whisper.cpp keeps token
timestamps in VAD-compressed time while remapping segment timestamps to real
time, so the two drift apart by however much silence VAD removed:

| | offset, first word vs its own segment |
|---|---|
| VAD on | −1.79s at the start, **−6.51s** by the end |
| VAD off | +0.00s to +0.20s |

Same episode, same model, same fields — on a *six-minute* episode. Every word
time would be early by a growing, episode-dependent, invisible amount.

Nothing is lost by turning it off. VAD's real value was suppressing the
unpunctuated collapse, and the prompt does that better (13/13 against 9/13).

### `response_format=verbose_json`

The server can return WebVTT directly. It is derived from the JSON here instead,
because per-word `start`/`end` come only from `verbose_json` and the decode
computes them either way — rendering to VTT throws them away.

Both artifacts come from one parse of one response. Asking for both formats
would mean two decodes, and whisper is not deterministic across runs, so their
cues and words could disagree in ways nothing downstream could detect.

Output per episode:

```
transcript.json     metadata plus the WebVTT string
words.jsonl.gz      {"w":" Doritos","s":1423.44,"e":1423.79,"p":0.94,"seg":118}
```

`p` is the decoder's own confidence and `seg` the cue the word came from, so the
sidecar joins back to the WebVTT without re-alignment. Roughly 274 KB per
episode before compression.

`e` is clamped to at least `s` on the way out, because whisper.cpp sometimes
stamps a word with its end before its start.
`whisper_exp_compute_token_level_timestamps` guards its monotonicity fix-up on
`j > 0`, so it never repairs a segment's first token, and it runs per segment,
so it cannot order across a segment boundary. Over a 17,553-episode corpus that
reached 2.1% of episodes, and about 4.8% of the words inside an affected one,
with 54% of the inversions sitting on the first token of their segment.

`e` is the half that moves, matching the `t1 = max(t0, t1)` whisper.cpp applies
to the tokens it does repair; lowering `s` instead would drag the word back
past whatever precedes it. The word is clamped rather than dropped, because it
still marks a real position in the stream and dropping it would shift every
index built on the sidecar. Sidecars written before this change keep their
inversions until the episode is decoded again, so a reader that must also cope
with the existing corpus still needs its own `min`/`max`.

### `token_timestamps`, `max_len`, `split_on_word`

whisper.cpp only applies `max_len` when `token_timestamps` is on — the wrap call
is nested inside `if (params.token_timestamps)` in `whisper_full`, so sending
`max_len` alone is silently ignored. Without them a whole episode can come back
as a single cue; 139 episodes in one corpus did, the worst covering over 7,200
seconds. A cue that long cannot carry a usable timestamp.

`split_on_word` cuts at word boundaries rather than mid-token.

### `beam_size=5` — not the server's greedy default

whisper-server defaults to greedy and whisper-cli to `beam_size=5`; both run
`strategy = beam_size > 1 ? BEAM_SEARCH : GREEDY`. Adopting the server without
sending this field silently put the pipeline on greedy.

Greedy's characteristic failure is repetition: it locks onto a phrase and emits
it for minutes. It hit 0.7%–5.0% of episodes per show across the first eleven
regenerated shows, and a repair pass running beam has fixed **57 of 57** of
them, most on the first attempt.

A paired trial on one show — same audio, same model, same fields, only
`beam_size` moved — found beam equal or better on healthy material too:

| | greedy | beam |
|---|---:|---:|
| median punctuation/word | 0.1546 | 0.1611 |
| sub-threshold loops cleared | — | 5 of 5 |
| clamped / unpunctuated episodes | 0 | 0 |

and it rescued a shredded episode outright, 0.74 s/cue to 2.42 with punctuation
0.109 → 0.208.

The costs are real but small: roughly 30% more decode time, and about 12% fewer
cues. Coarser cues used to matter because the cue was the floor on boundary
precision; with per-word times in `words.jsonl.gz` it no longer is.

Set `beamSize = 1` for greedy.

## Platform acceleration

Acceleration is determined by how the whisper.cpp `server` binary was compiled:

- **macOS (Apple Silicon)** — Metal acceleration, built in by default
- **Linux** — CUDA acceleration if built with `WHISPER_CUDA=1`; CPU fallback otherwise

## Transcribing

With `pipeline/.env` filled in (see [Pipeline configuration](#pipeline-configuration) below), no arguments are needed:

```bash
./transcribe
```

`./transcribe` builds the distribution and runs it, so a source change can never leave you
running a stale binary. It passes its arguments straight through and exits with the
pipeline's own status, and it resolves `.env` and relative paths against your current
directory — it is the long form below with the path memorised for you:

```bash
./gradlew :pipeline:installDist
./pipeline/build/install/pipeline/bin/pipeline
```

`./gradlew :pipeline:run` also works, with the caveats under
[Arguments](#arguments) below.

Every `.env` setting also has a flag, which takes precedence over it — run
`./transcribe --help` for the list.

### Running two instances at once

Give each instance its own `pods.yaml` and its own whisper.cpp server. In one terminal:

```bash
./transcribe --config ~/pods-a.yaml --whisper-url http://localhost:8081
```

and in another:

```bash
./transcribe --config ~/pods-b.yaml --whisper-url http://localhost:8082
```

Don't use `./gradlew :pipeline:run` for this — two concurrent Gradle invocations in one
checkout serialise on the project lock. `./transcribe` takes that lock only for its build
and has released it by the time the pipeline starts, so a second launch waits a second at
most.

Both can share one data directory, but nothing coordinates them: list a show in only one
of the two files. If both walk the same feed they will download the same episode to the
same `audio.mp3.part` staging file, and whichever finishes first deletes the other's.

## Indexing

```bash
python3 index.py /path/to/data_directory

# Specify a custom database path
python3 index.py /path/to/data_directory --db /path/to/podcasts.db
```

The database defaults to `podcasts.db` inside the data directory. Designed to run directly on the machine hosting the files to avoid network filesystem overhead.

## Serving the web UI

Copy `.env.example` to `.env` and fill in your values (`.env` is gitignored):

```bash
cd web
cp .env.example .env
```

```ini
APP_DB_PATH=/path/to/podcasts.db
APP_AUDIO_BASE_URL=http://your-nas:9280
```

Quarkus picks up `.env` automatically. Alternatively, override properties inline:

**Development** (live reload on template/code changes):

```bash
./gradlew :web:quarkusDev
```

**Production** — build a runnable JAR then launch it:

```bash
./gradlew :web:build
java -jar web/build/quarkus-app/quarkus-run.jar
```

Configuration properties can also be overridden at launch without editing the file:

```bash
java -Dapp.db.path=/data/podcasts.db \
     -Dapp.audio.base-url=http://nas:9280 \
     -jar web/build/quarkus-app/quarkus-run.jar
```

## Pipeline configuration

### Environment (`.env`)

Copy `pipeline/.env.example` to `pipeline/.env` and fill in your values (`.env` is gitignored):

```ini
PIPELINE_DATA_DIRECTORY=/path/to/download-directory
PIPELINE_WHISPER_SERVER_URL=http://localhost:8080
PIPELINE_CONFIG_PATH=/path/to/pods.yaml
#PIPELINE_VERBOSE=true
```

`PIPELINE_VERBOSE` is optional; when set to a non-blank value it overrides the `verbose` value from
`pods.yaml`. Leave it commented out (or blank) to let `pods.yaml` decide — note that any value other
than `true` counts as `false`, so `PIPELINE_VERBOSE=false` will override `verbose: true`.

The `.env` file is resolved relative to the working directory: `pipeline/.env` is tried
first (running from the repo root), then `./.env` (running from inside `pipeline/`, or
next to an installed distribution). A missing `.env` is fine as long as the arguments
below supply the three required values.

### Arguments

| Flag | Overrides |
| --- | --- |
| `--config <path>` | `PIPELINE_CONFIG_PATH` |
| `--data-dir <path>` | `PIPELINE_DATA_DIRECTORY` |
| `--whisper-url <url>` | `PIPELINE_WHISPER_SERVER_URL` |
| `--verbose` / `--no-verbose` | `PIPELINE_VERBOSE` |
| `--recover-orphans` / `--no-recover-orphans` | `recover_orphans` in `pods.yaml` |
| `--orphan-limit <n>` | `orphan_recovery_limit` in `pods.yaml` |

Precedence is argument, then `.env`, then `pods.yaml`. A flag that is not passed falls
through, so `--whisper-url` alone leaves everything else coming from `.env`.

Under Gradle the flags go through `--args`. Gradle splits that string itself rather than
handing it to a shell, so write `$HOME` or an absolute path — a `~` inside the quotes
arrives at the pipeline literally and the config file is not found:

```bash
./gradlew :pipeline:run --args="--config $HOME/pods-a.yaml --whisper-url http://localhost:8081"
```

A bad flag exits `2` with its message on stderr. Unusable configuration exits `1` — that
covers a missing `--config` as well as a data directory that is missing or not writable. A
run that found nothing new to transcribe exits `0`, so a wrapper script can tell a failed
launch from a quiet one.

### `pods.yaml`

- `verbose` — enable debug logging (optional, default `false`; overridden by `PIPELINE_VERBOSE` when that is set)
- `skip_after_consecutive` — stop walking a feed once this many consecutive already-transcribed episodes are seen (optional, default `20`)
- `exclude_title_keywords` — titles matching any of these are skipped for every feed (optional, see [Non-content exclusions](#non-content-exclusions); set to `[]` to disable)
- `min_episode_duration_seconds` — skip episodes shorter than this (optional, default `150`; set to `0` to disable)
- `recover_orphans` — transcribe episodes that aged out of their feed before they were processed (optional, default `true`; see [Orphan recovery](#orphan-recovery))
- `orphan_recovery_limit` — at most this many orphans per run, across all podcasts (optional, default `0`, meaning no limit)
- `podcasts` — list of RSS feeds to process, each with `name`, `url`, optional `collections`, optional `excludes`, and an optional `min_episode_duration_seconds` that overrides the global floor

`name` becomes the show's directory name, so changing it moves every episode of
that feed. Re-capitalising it used to create a *second* directory for the same
show; the pipeline now reuses a directory that differs only by case, and logs
when it does. Renaming it any other way still starts a fresh directory and
re-transcribes the feed.

### Non-content exclusions

Two filters run before an episode is downloaded, so excluded episodes cost nothing.

`exclude_title_keywords` matches whole words, case-insensitively, against the episode
title. Whole-word matching is what makes the list safe to apply globally: a substring
match on `repeat` also swallows "Repeating FRB Mystery", and on `archives` it swallows
"Inside the Archives", an actual interview series. The default list is trailers,
cross-promos and repeat markers: `trailer`, `introducing`, `encore`, `classic episode`,
`rewind`, `re-release`, `re-run`, `rerun`, `rebroadcast`, `best of`, `repeat`, `replay`,
`from the archives`. Setting the key replaces the list rather than adding to it; the
per-podcast `excludes` list is separate, still a plain substring match, and still applies
on top.

`coming soon` is deliberately *not* in the default list. It reads as a safe global term
but matches Planetary Radio's "2012 DA14--Coming Soon to a Planet Near You!", a real
29-minute episode. Every genuine hit for it sits in one of two feeds, so it belongs in
their `excludes` rather than the global list.

`min_episode_duration_seconds` uses the feed's `itunes:duration`. An episode whose feed
omits the tag is never filtered on length. The `150` default sits at the point where
short-form content starts to outnumber promos — below it a feed is almost entirely
trailers, hiatus notices and "coming soon" stubs. Feeds that publish genuine short-form
episodes need the floor lifted per podcast:

```yaml
- name: A Short-Form Show
  url: https://example.com/feed.rss
  min_episode_duration_seconds: 0
```

### Duplicate GUIDs

`_id` is `md5(guid)[:8]`, and the episode table is keyed on it. Publishers do
occasionally ship two entries under one GUID — HBR IdeaCast has two such pairs —
which under `INSERT OR REPLACE` silently collapsed them into a single row, losing
one episode from search while its transcript sat on disk.

`index.py` now suffixes the later members of a clashing group (`<id>-2`, `-3`, …),
ordered by audio path so the ids are stable across re-indexes, and warns on stderr
naming every episode involved. Both episodes stay searchable and keep a working
`/episode/{id}` permalink. Widening the hash would fix nothing here — the inputs
really are identical — and would rename every episode directory, forcing a full
re-transcription.

### Skip heuristic

Feeds are typically ordered newest-first. Rather than stat'ing every episode directory (expensive for feeds with thousands of entries), the transcriber walks the feed and stops on a podcast once it sees `skip_after_consecutive` transcribed episodes in a row. The counter resets on any gap, so a cancelled run that left untranscribed holes will be picked up on the next invocation.

### Orphan recovery

The pipeline only transcribes what the feed offers, and publishers age entries out on hard
caps or date cutoffs. An episode downloaded but not yet transcribed when that happens is
stranded: its directory holds an `audio.mp3` no later run will ever look at. 659 of 17,517
episode directories were in that state when this was written.

After walking a feed, the pipeline lists the podcast's directory once and compares each
episode directory's `YYYY-MM-DD-<hex8>` prefix against the feed. Directories whose prefix
is absent are opened and, if they hold audio but no transcript, transcribed in place —
newest first, and never renamed, because indexed rows already point at the existing path.

The feed still describes the show, so every `podcast_*` field is accurate. The entry is
gone, so only what the directory name carries can be recovered:

| Recovered | Source |
| --- | --- |
| `_id` | the `<hex8>` in the directory name — the same id the episode always had |
| `episode_published_on` | the date in the directory name |
| `episode_title` | the rest of the directory name, dashes back to spaces (lossy: punctuation is gone) |
| `episode_duration` | where the decoded speech ends — approximate, and short of the file by any trailing silence |
| `all_tags` | feed-level categories only |
| `episode_metadata_recovered` | `true`, present only on recovered files |

`episode_audio_link`, `episode_web_link`, `episode_image`, `episode_summary`,
`episode_subtitle`, `episode_authors`, `episode_number`, `episode_season` and
`episode_type` are written as `null`. The web UI hides a null field and would render an
empty string as a dead link, so the distinction matters.

Audio that decodes to nothing leaves a `recovery-failed` marker in the directory. Without
it the file would be re-uploaded to whisper on every run forever — an orphan has no feed
entry whose download could fail and stop it. A transcriber *error* (server down, timeout)
writes no marker and is retried.

Two situations are reported as errors rather than recovered, because the feed entry has
better metadata than the directory name ever could:

- a directory whose id is in the feed under a **different date** — a publisher re-issued or
  re-dated an old episode
- an episode still in the feed, still untranscribed, that `skip_after_consecutive` stopped
  before reaching. Raise the threshold to pick it up.

Recovery is on by default and costs about 20 seconds across a full run, because only the
directories absent from the feed are opened. `--no-recover-orphans` turns it off;
`--orphan-limit <n>` bounds how many a single run will transcribe, which is worth setting
when the backlog is large enough to crowd out new episodes.

### Error log

Warnings and errors are mirrored to `<data-dir>/logs/pipeline-errors.log`, rolling daily
and keeping two weeks, and the run prints a count of both when it finishes. A 13,000-episode
run otherwise buries its failures in scrollback.

## Output structure

For each episode, the following files are created:

```
{data_directory}/{podcast_name}/{YYYY-MM-DD-<hex8>-episode-title}/
    audio.mp3                      # Downloaded audio, kept as-is and served by the web module
    transcript.json                # Full metadata + WebVTT transcript
    words.jsonl.gz                 # One line per word, with its own start/end
    recovery-failed                # Only when recovery found no speech in the audio
```

The `episode_transcript` field in `transcript.json` is a raw WebVTT string,
rendered from the same `verbose_json` response that produced `words.jsonl.gz`.

`words.jsonl.gz` is written **before** `transcript.json`, because the latter
existing is what marks an episode done — so a crash between the two leaves the
episode to be redone rather than permanently without its sidecar. A sidecar
write failure is logged and not fatal: the transcript is the artifact the
pipeline exists to produce.

The `<hex8>` in the directory name is `md5(entry.uri)` truncated to 8
characters. It is part of a path, not a unique key: date and title slug
disambiguate it.

## Testing

```bash
./gradlew test
```

## Linting

```bash
./gradlew ktlintCheck
```

To auto-format:

```bash
./gradlew ktlintFormat
```

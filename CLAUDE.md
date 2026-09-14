# CLAUDE.md

## Project overview

Three-stage pipeline for podcast transcription and full-text search:

1. **`pipeline`** (Kotlin) — downloads RSS episodes and POSTs the MP3 to a whisper.cpp HTTP server, which decodes and resamples it server-side. Writes `transcript.json` alongside each audio file, keeping the MP3.
2. **`index.py`** (Python 3) — walks the data directory, reads `transcript.json` files, and loads them into a SQLite FTS5 database.
3. **`web`** (Kotlin / Quarkus) — REST server on port 8080 serving a full-text search UI over the SQLite database.

## Build

```bash
./gradlew build          # build everything
./gradlew ktlintCheck    # lint check (ktlint is enforced)
./gradlew ktlintFormat   # auto-fix lint issues
```

## Running

### Pipeline (transcription)

```bash
./transcribe
```

`--dry-run` reports what a run would do — every filter applied, nothing downloaded,
decoded or created, and the whisper server never contacted.

`./transcribe` is a wrapper that runs `:pipeline:installDist` and execs the launcher,
passing arguments through. `./gradlew :pipeline:run` still works for a single instance.

Configuration comes from `pipeline/.env` — copy `pipeline/.env.example` and fill in:
- `PIPELINE_CONFIG_PATH` — path to your `pods.yaml`
- `PIPELINE_DATA_DIRECTORY` — where audio and transcripts are stored
- `PIPELINE_WHISPER_SERVER_URL` — URL of the whisper HTTP server
- `PIPELINE_VERBOSE` — set to `true` to enable debug logging

Each of those has a command-line equivalent that takes precedence (`--config`, `--data-dir`,
`--whisper-url`, `--verbose`/`--no-verbose`), which is how two instances run side by side:

```bash
./transcribe --config ~/pods-b.yaml --whisper-url http://localhost:8082
```

Configure `pods.yaml` with:
- `podcasts` — list of RSS feeds with `name`, `url`, and `collections` tags
- `skip_after_consecutive` — stop processing a feed after N already-transcribed episodes in a row (default: 20)
- `recover_orphans` — transcribe episodes that aged out of the feed before being processed (default: true)
- `orphan_recovery_limit` — cap orphan recoveries per run across all podcasts (default: 0, no limit)
- `initial_prompt` — the prompt sent with a feed's decodes, written in its `language`; set per podcast or top-level. A podcast that sets it must set `language` too, and neither may be `auto`

`language` is checked against whisper's own code list at startup — an unrecognised one
would otherwise decode in the wrong language silently.

- `notify_url` — POST the run's summary line here as text/plain when a run finishes (optional)

Warnings and errors are mirrored to `<data-dir>/logs/pipeline-errors.log`, and the run
prints a warning/error count when it finishes. Every run also writes what it did to
`<data-dir>/logs/run-<stamp>.json`, copied to `logs/latest-run.json`.

`--retranscribe-flagged` selects least-recently-attempted first, tracked by a
`retranscribe-attempted` file per episode, so its limit is a rolling window.
`--retranscribe-force` keeps a worse-scoring decode for explicitly named targets, and is
refused alongside `--retranscribe-flagged`.

No external tools are required on `PATH` — transcription is HTTP-only against the whisper.cpp server, which handles audio decoding itself.

### Indexer

```bash
python3 index.py /path/to/data_directory [--db /path/to/podcasts.db]
```

Requires an FTS5-capable SQLite. The script prefers `pysqlite3` and falls back to the
stdlib `sqlite3` module, exiting with a clear error if neither build has FTS5.

Run this after the pipeline has produced new transcripts.

### Web server

Development (hot reload):
```bash
./gradlew :web:quarkusDev
```

Production:
```bash
./gradlew :web:build
java -jar web/build/quarkus-app/quarkus-run.jar
```

The web module reads `web/.env`:
```
APP_DB_PATH=/path/to/podcasts.db
APP_AUDIO_BASE_URL=http://your-audio-server:port
```

## Code style

- Kotlin throughout (JVM 21), ktlint enforced — run `./gradlew ktlintFormat` before committing
- Always run `./gradlew ktlintFormat && ./gradlew test` before committing
- `pipeline` JVM heap is set to 4GB by default (`-Xmx4g`) to handle large whisper model loads
- Thymeleaf is used as a plain library in `web` (no Quarkiverse extension) via a hand-rolled CDI producer
- **Comment sparingly.** Do not narrate what the code already says, and do not restate a
  design decision at each site that follows it — state it once, in `docs/ROADMAP.md` or
  the PR, and let the code stand. A comment earns its place only when it records
  something the reader cannot see: an external system's behaviour, a measurement, or a
  trap that would otherwise be refactored away. Prefer one line to a block; no KDoc on a
  test whose name already says what it asserts.

## Planned work

`docs/ROADMAP.md` describes the features planned next for each module, with the files,
design, tests and gotchas for each. Pick from it rather than re-deriving the list.

## Git workflow

- Always create a feature branch from `main` before making changes — never commit directly to `main`
- Branch naming convention: `baz8080/<short-description>`

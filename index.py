#!/usr/bin/env python3
"""Index podcast transcripts into a SQLite FTS5 database.

Usage:
    python3 index.py /path/to/data_directory [--db podcasts.db]

Walks the data directory for transcript.json files and inserts them into
a SQLite database with full-text search. Designed to run directly on the
machine hosting the files to avoid network filesystem overhead.
"""

import argparse
import json
import os
import re
import stat
import sys
import time

try:
    import pysqlite3 as sqlite3
except ImportError:
    # Fall back to the stdlib module — fine anywhere its SQLite has FTS5
    # (checked at startup in main()).
    import sqlite3


SCHEMA_EPISODES = """
CREATE TABLE IF NOT EXISTS episodes (
    id TEXT PRIMARY KEY,
    podcast_title TEXT,
    podcast_link TEXT,
    podcast_language TEXT,
    podcast_copyright TEXT,
    podcast_author TEXT,
    podcast_image TEXT,
    podcast_type TEXT,
    podcast_collections TEXT,
    episode_title TEXT,
    episode_published_on TEXT,
    episode_audio_link TEXT,
    episode_web_link TEXT,
    episode_image TEXT,
    episode_summary TEXT,
    episode_subtitle TEXT,
    episode_authors TEXT,
    episode_number INTEGER,
    episode_season INTEGER,
    episode_type TEXT,
    episode_duration INTEGER,
    episode_transcript TEXT,
    episode_transcript_plain TEXT,
    episode_relative_audio_path TEXT,
    all_tags TEXT,
    -- Where the row came from and what it looked like, so a re-index can tell
    -- which files it still has to read. source_id is the _id as written, before
    -- disambiguate_ids has had a chance to suffix it.
    source_path TEXT,
    source_mtime REAL,
    source_size INTEGER,
    source_id TEXT
)
"""

# The files walked but deliberately not indexed -- no _id, no transcript, or
# unreadable. Without them a skipped file looks new on every run, and gets
# opened again every time, which is the cost this is all here to avoid.
SCHEMA_SKIPPED = """
CREATE TABLE IF NOT EXISTS skipped_sources (
    source_path TEXT PRIMARY KEY,
    source_mtime REAL,
    source_size INTEGER
)
"""

# Written in the same commit as the FTS rebuild. Creating episodes_fts is DDL
# and commits on its own, so an interrupt between the two leaves the table
# there and empty -- and every later run would see a database that looks
# complete and report an index with nothing in it as up to date.
SCHEMA_META = """
CREATE TABLE IF NOT EXISTS index_meta (
    key TEXT PRIMARY KEY,
    value TEXT
)
"""

# FTS indexes episode_transcript_plain (VTT timing lines stripped) rather than
# episode_transcript (raw VTT), so searches never match on timestamps.
SCHEMA_FTS = """
CREATE VIRTUAL TABLE IF NOT EXISTS episodes_fts USING fts5(
    episode_title,
    episode_transcript_plain,
    podcast_title,
    all_tags,
    content='episodes',
    content_rowid='rowid'
)
"""

COLUMNS = (
    "id", "podcast_title", "podcast_link", "podcast_language", "podcast_copyright",
    "podcast_author", "podcast_image", "podcast_type", "podcast_collections",
    "episode_title", "episode_published_on", "episode_audio_link", "episode_web_link",
    "episode_image", "episode_summary", "episode_subtitle", "episode_authors",
    "episode_number", "episode_season", "episode_type", "episode_duration",
    "episode_transcript", "episode_transcript_plain", "episode_relative_audio_path",
    "all_tags", "source_path", "source_mtime", "source_size", "source_id",
)

# Built from COLUMNS rather than written out: an incremental run needs the same
# column list twice, and two hand-maintained lists is one more than can be kept
# in step.
INSERT_SQL = "INSERT OR REPLACE INTO episodes ({}) VALUES ({})".format(
    ", ".join(COLUMNS),
    ", ".join(":" + c for c in COLUMNS),
)

# By rowid, not by id: episodes_fts is an external-content table keyed on the
# rowid, and INSERT OR REPLACE would allocate a new one and strand the index.
UPDATE_SQL = "UPDATE episodes SET {} WHERE rowid = :rowid".format(
    ", ".join("{0} = :{0}".format(c) for c in COLUMNS),
)

# The columns episodes_fts indexes, in its own order.
FTS_COLUMNS = ("episode_title", "episode_transcript_plain", "podcast_title", "all_tags")

# Past this share of the corpus, one rebuild beats a delete and an insert per
# row -- and sidesteps any chance of the hand-maintained index drifting.
FTS_REBUILD_SHARE = 0.2


_VTT_TIMING_RE = re.compile(r"\d{2}:\d{2}:\d{2}\.\d{3} --> \d{2}:\d{2}:\d{2}\.\d{3}")


def strip_vtt(text):
    """Return plain text from a WebVTT string, suitable for FTS indexing."""
    words = []
    for line in text.splitlines():
        s = line.strip()
        if s and s != "WEBVTT" and not _VTT_TIMING_RE.match(s):
            words.append(s)
    return " ".join(words)


def join_list(value):
    """Join a list into a comma-separated string, or return None."""
    if not isinstance(value, list):
        return None
    return ", ".join(str(item) for item in value if item is not None)


def disambiguate_ids(records):
    """Give every record a unique `id`, warning about every clash.

    source_id is md5(guid)[:8], so two feed entries sharing a GUID -- which
    publishers do get wrong -- collapse into one row under INSERT OR REPLACE
    and the loser silently vanishes from search. Suffix the later ones instead
    so both stay reachable, ordered by audio path so the ids are stable across
    re-indexes.

    An incremental run passes the records it read alongside stubs for the files
    it did not, so a clash is resolved the same way whether or not the episode
    it lands on happened to change.
    """
    by_id = {}
    for record in records:
        record["id"] = record["source_id"]
        by_id.setdefault(record["source_id"], []).append(record)

    clashes = 0
    for episode_id, group in by_id.items():
        if len(group) == 1:
            continue
        clashes += 1
        # source_path breaks a tie the audio path cannot: both are NULL often
        # enough, and a stable sort would otherwise settle it on the order the
        # records happened to be assembled in, which differs between an
        # incremental run and a rebuild. The id is a URL.
        group.sort(key=lambda e: (e["episode_relative_audio_path"] or "", e["source_path"]))
        print(f"  WARNING: {len(group)} episodes share _id {episode_id}:", file=sys.stderr)
        for n, record in enumerate(group, start=1):
            if n > 1:
                record["id"] = f"{episode_id}-{n}"
            print(f"    {record['id']}  {record['episode_relative_audio_path']}", file=sys.stderr)

    if clashes:
        print(f"  {clashes} duplicate _id group(s) disambiguated", file=sys.stderr)
    return records


def scan_sources(data_dir):
    """Map every transcript.json under the data directory to its stamp.

    Stats only. Reading these is the cost an incremental run exists to avoid,
    and on the corpus sizes in the README it is minutes of network I/O.

    Size as well as mtime: a filesystem whose timestamps are coarse can give a
    rewrite the same mtime as the write before it, and a transcript that was
    re-indexed in between would otherwise stay stale until the next --full.

    Returns the stamps alongside the paths whose state could not be
    established. A file that fails to stat has not been shown to be gone, and
    an incremental run that treated the two alike would drop its row on a
    share that blinked.
    """
    sources, unknown = {}, set()

    for podcast_name in sorted(os.listdir(data_dir)):
        podcast_path = os.path.join(data_dir, podcast_name)
        try:
            episode_names = os.listdir(podcast_path)
        except (NotADirectoryError, FileNotFoundError):
            continue
        except OSError as e:
            print(f"  WARNING: Could not list {podcast_path}, leaving it as it was: {e}", file=sys.stderr)
            unknown.add(podcast_name + os.sep)
            continue

        for episode_name in episode_names:
            json_path = os.path.join(podcast_path, episode_name, "transcript.json")
            relative = os.path.relpath(json_path, data_dir)
            try:
                info = os.stat(json_path)
            except (NotADirectoryError, FileNotFoundError):
                continue
            except OSError as e:
                print(f"  WARNING: Could not stat {json_path}, leaving it as it was: {e}", file=sys.stderr)
                unknown.add(relative)
                continue
            if not stat.S_ISREG(info.st_mode):
                continue
            sources[relative] = (info.st_mtime, info.st_size)

    return sources, unknown


def is_unknown(source_path, unknown):
    """Whether this run failed to establish what is at this path."""
    if source_path in unknown:
        return True
    return any(source_path.startswith(p) for p in unknown if p.endswith(os.sep))


def read_episode(data_dir, source_path, stamp):
    """The row a transcript.json becomes, paired with how it went.

    ("error", None) is a file that could not be read this time, which is not
    the same as one that cannot be indexed: a share that blinks must not cost
    an episode its row, and must not be remembered as skipped either, or it
    would never be looked at again.
    """
    json_path = os.path.join(data_dir, source_path)
    try:
        with open(json_path, "r") as f:
            episode = json.load(f)
    except OSError as e:
        print(f"  WARNING: Could not read {json_path}, leaving it as it was: {e}", file=sys.stderr)
        return "error", None
    except json.JSONDecodeError as e:
        print(f"  WARNING: Skipping {json_path}, it is not valid JSON: {e}", file=sys.stderr)
        return "skip", None

    if not isinstance(episode, dict):
        print(f"  WARNING: Skipping {json_path}: it is not a JSON object", file=sys.stderr)
        return "skip", None

    transcript = episode.get("episode_transcript")
    if not transcript:
        return "skip", None

    episode_id = episode.get("_id")
    if not episode_id:
        print(f"  WARNING: Skipping {json_path}: missing _id", file=sys.stderr)
        return "skip", None

    return "ok", {
        "podcast_title": episode.get("podcast_title"),
        "podcast_link": episode.get("podcast_link"),
        "podcast_language": episode.get("podcast_language"),
        "podcast_copyright": episode.get("podcast_copyright"),
        "podcast_author": episode.get("podcast_author"),
        "podcast_image": episode.get("podcast_image"),
        "podcast_type": episode.get("podcast_type"),
        "podcast_collections": join_list(episode.get("podcast_collections")),
        "episode_title": episode.get("episode_title"),
        "episode_published_on": episode.get("episode_published_on"),
        "episode_audio_link": episode.get("episode_audio_link"),
        "episode_web_link": episode.get("episode_web_link"),
        "episode_image": episode.get("episode_image"),
        "episode_summary": episode.get("episode_summary"),
        "episode_subtitle": episode.get("episode_subtitle"),
        "episode_authors": join_list(episode.get("episode_authors")),
        "episode_number": episode.get("episode_number"),
        "episode_season": episode.get("episode_season"),
        "episode_type": episode.get("episode_type"),
        "episode_duration": episode.get("episode_duration"),
        "episode_transcript": transcript,
        "episode_transcript_plain": strip_vtt(transcript),
        "episode_relative_audio_path": episode.get("episode_relative_audio_path"),
        "all_tags": join_list(episode.get("all_tags")),
        "source_path": source_path,
        "source_mtime": stamp[0],
        "source_size": stamp[1],
        "source_id": episode_id,
    }


def fts_values(conn, rowid):
    """What episodes_fts holds for a row, which is what deleting it needs."""
    return conn.execute(
        f"SELECT {', '.join(FTS_COLUMNS)} FROM episodes WHERE rowid = ?", (rowid,)
    ).fetchone()


def fts_delete(conn, rowid, values):
    conn.execute(
        "INSERT INTO episodes_fts(episodes_fts, rowid, {}) VALUES('delete', ?, ?, ?, ?, ?)".format(
            ", ".join(FTS_COLUMNS)
        ),
        (rowid, *values),
    )


def fts_insert(conn, rowid, values):
    conn.execute(
        "INSERT INTO episodes_fts(rowid, {}) VALUES (?, ?, ?, ?, ?)".format(", ".join(FTS_COLUMNS)),
        (rowid, *values),
    )


def indexed_count(conn):
    """How many episodes are already indexed, zero if there is no table yet."""
    if not conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='episodes'"
    ).fetchone():
        return 0
    return conn.execute("SELECT count(*) FROM episodes").fetchone()[0]


def schema_is_current(conn):
    """Whether this database can be updated in place rather than rebuilt."""
    tables = {
        row[0]
        for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
    }
    if not {"episodes", "episodes_fts", "skipped_sources", "index_meta"} <= tables:
        return False
    built = conn.execute("SELECT value FROM index_meta WHERE key = 'fts_built'").fetchone()
    if built is None:
        return False
    columns = {row[1] for row in conn.execute("PRAGMA table_info(episodes)")}
    return {"source_path", "source_mtime", "source_size", "source_id"} <= columns


def load_stored(conn):
    """What each indexed row remembers about the file it came from.

    None when any row predates the tracking columns: the rest of the corpus
    cannot be trusted to be up to date either, so the caller rebuilds.
    """
    stored = {}
    for rowid, source_path, episode_id, source_id, mtime, size, audio in conn.execute(
        "SELECT rowid, source_path, id, source_id, source_mtime, source_size, "
        "episode_relative_audio_path FROM episodes"
    ):
        if source_path is None:
            return None
        stored[source_path] = {
            "rowid": rowid,
            "id": episode_id,
            "source_id": source_id,
            "stamp": (mtime, size),
            "audio": audio,
        }
    return stored


def load_skipped(conn):
    """The files a previous run walked and could not index, with their stamps."""
    return {
        row[0]: (row[1], row[2])
        for row in conn.execute("SELECT source_path, source_mtime, source_size FROM skipped_sources")
    }


def run_incremental(conn, data_dir, sources, unknown, db_path):
    """Bring the database up to date by reading only what changed.

    False when it cannot, and the caller should rebuild instead.
    """
    stored = load_stored(conn)
    if stored is None:
        return False
    skipped = load_skipped(conn)

    # Both tables together are what the last run knew about, and a file is only
    # worth opening again if it is new to both or has changed since.
    known = {p: row["stamp"] for p, row in stored.items()}
    known.update(skipped)

    # A path this run could not stat has not been shown to be gone.
    gone = {p for p in set(known) - set(sources) if not is_unknown(p, unknown)}
    fresh = set(sources) - set(known)
    touched = {p for p in set(sources) & set(known) if known[p] != sources[p]}

    if not (gone or fresh or touched):
        print(f"Already up to date: {len(sources)} transcripts, none changed")
        return True

    print(f"{len(fresh)} new, {len(touched)} changed, {len(gone)} removed")

    records, unindexable = [], []
    for source_path in sorted(fresh | touched):
        status, record = read_episode(data_dir, source_path, sources[source_path])
        if status == "ok":
            records.append(record)
        elif status == "skip":
            unindexable.append(source_path)
        # "error" is left entirely alone: no row dropped, no stamp written, so
        # the next run finds it unchanged-but-untracked and tries again.

    # A file that has stopped being indexable -- unreadable now, or its
    # transcript gone -- would otherwise leave its old row behind for good.
    read = {r["source_path"] for r in records}
    drop = (gone & set(stored)) | {p for p in unindexable if p in stored}
    forget = (gone & set(skipped)) | (read & set(skipped))

    # The same refusal a full rebuild makes when nothing is indexable, and
    # reachable here in a way it is not there: a restore that leaves every
    # transcript truncated but freshly stamped reads as the whole corpus going
    # unindexable at once, and this is the routine run, not the deliberate one.
    remaining = len(set(stored) - drop) + len([r for r in records if r["source_path"] not in stored])
    if stored and remaining == 0:
        # No remedy offered on purpose: a rebuild refuses this too, and deleting
        # the database and re-running leaves no tables at all when there is
        # nothing indexable to put in them. Far likelier that the corpus is
        # wrong than that it is meant to be empty.
        print(
            f"Every indexed episode would be removed; leaving {db_path} as it is.\n"
            "Check the data directory is mounted and its transcripts are intact.",
            file=sys.stderr,
        )
        return True

    # The files that are not being read still take part in deciding ids, so a
    # clash resolves the way a full rebuild would resolve it.
    stubs = [
        {
            "source_path": p,
            "source_id": stored[p]["source_id"],
            "episode_relative_audio_path": stored[p]["audio"],
        }
        for p in stored
        if p not in drop and p not in read
    ]
    disambiguate_ids(records + stubs)

    affected = len(records) + len(drop)
    bulk = affected > len(stored) * FTS_REBUILD_SHARE
    if bulk:
        of_what = f" of {len(stored)}" if stored else ""
        print(f"  Rebuilding the FTS index: {affected}{of_what} rows are affected")

    with conn:
        # Every id that is about to move goes somewhere unique first: a clash
        # group gaining a member shuffles the suffixes, and two rows would
        # otherwise hold the same id for the moment between their updates.
        final_ids = {r["source_path"]: r["id"] for r in records + stubs}
        for source_path, new_id in final_ids.items():
            row = stored.get(source_path)
            if row and row["id"] != new_id:
                conn.execute(
                    "UPDATE episodes SET id = ? WHERE rowid = ?",
                    (f"__reindexing__{row['rowid']}", row["rowid"]),
                )

        for source_path in sorted(drop):
            rowid = stored[source_path]["rowid"]
            if not bulk:
                fts_delete(conn, rowid, fts_values(conn, rowid))
            conn.execute("DELETE FROM episodes WHERE rowid = ?", (rowid,))

        for record in records:
            row = stored.get(record["source_path"])
            if row:
                if not bulk:
                    fts_delete(conn, row["rowid"], fts_values(conn, row["rowid"]))
                conn.execute(UPDATE_SQL, {**record, "rowid": row["rowid"]})
                rowid = row["rowid"]
            else:
                rowid = conn.execute(INSERT_SQL, record).lastrowid
            if not bulk:
                fts_insert(conn, rowid, [record[c] for c in FTS_COLUMNS])

        # Only the id moved, and episodes_fts does not index it, so these rows
        # need no more than the column write.
        for stub in stubs:
            row = stored[stub["source_path"]]
            if row["id"] != stub["id"]:
                conn.execute(
                    "UPDATE episodes SET id = ? WHERE rowid = ?", (stub["id"], row["rowid"])
                )

        if bulk:
            conn.execute("INSERT INTO episodes_fts(episodes_fts) VALUES('rebuild')")

        for source_path in sorted(forget):
            conn.execute("DELETE FROM skipped_sources WHERE source_path = ?", (source_path,))
        for source_path in unindexable:
            conn.execute(
                "INSERT OR REPLACE INTO skipped_sources(source_path, source_mtime, source_size) "
                "VALUES (?, ?, ?)",
                (source_path, *sources[source_path]),
            )

    print(f"Done: {conn.execute('SELECT count(*) FROM episodes').fetchone()[0]} episodes indexed")
    return True


def run_full(conn, data_dir, sources, unknown, db_path):
    """Rebuild both tables from every transcript.json."""
    # Collect before dropping anything. The rebuild below is destructive and
    # episodes_fts is only recreated at the very end, so bailing out after the
    # drops would leave a previously working database truncated and without its
    # FTS index -- and every episode can be skipped legitimately (e.g. none of
    # the transcript.json files carry an _id).
    episodes, skipped, failed = [], [], []
    for source_path in sorted(sources):
        status, record = read_episode(data_dir, source_path, sources[source_path])
        if status == "ok":
            episodes.append(record)
        elif status == "skip":
            skipped.append(source_path)
        else:
            failed.append(source_path)
    print(f"Found {len(episodes)} episodes to index")

    # A rebuild keeps only what it read, so anything it could not look at this
    # time is dropped -- and this is the path every database without the
    # tracking columns takes, which is every first run after this change. With
    # nothing yet indexed there is nothing to lose and the next run picks the
    # rest up; with rows already there, losing them is not recoverable.
    blind = sorted(set(failed) | unknown)
    if blind and indexed_count(conn):
        print(
            f"{len(blind)} path(s) could not be read this time, and a rebuild keeps only\n"
            f"what it reads. Leaving {db_path} untouched; re-run when they are reachable.\n"
            + "".join(f"  {p}\n" for p in blind[:10]),
            file=sys.stderr,
        )
        return
    disambiguate_ids(episodes)

    if not episodes:
        print("Nothing to index; leaving the existing database untouched")
        return

    # Use raw sqlite_master to drop FTS table safely — DROP TABLE on a
    # virtual table requires the module to be loaded, which may not be
    # available if the table was created with a different FTS version.
    fts_exists = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='episodes_fts'"
    ).fetchone()
    if fts_exists:
        try:
            conn.execute("DROP TABLE IF EXISTS episodes_fts")
        except sqlite3.OperationalError as e:
            # The existing episodes_fts was built by an FTS module this SQLite
            # cannot load (e.g. a legacy FTS4 table under an FTS5-only build),
            # so DROP TABLE cannot take it apart. Editing sqlite_master by hand
            # is not an option: the Python sqlite3 module refuses writes to it
            # even with PRAGMA writable_schema=ON (unlike the sqlite3 CLI), and
            # dropping only the shadow tables would leave a dangling schema
            # entry that breaks the CREATE VIRTUAL TABLE below. Rebuilding from
            # scratch is safe here -- the database is derived data, rebuilt in
            # full on every run.
            print(
                f"ERROR: Cannot drop the existing episodes_fts table: {e}\n"
                f"       Delete {db_path} and re-run to rebuild from scratch.",
                file=sys.stderr,
            )
            conn.close()
            sys.exit(1)
    conn.execute("DROP TABLE IF EXISTS episodes")
    conn.execute("DROP TABLE IF EXISTS skipped_sources")
    conn.execute("DROP TABLE IF EXISTS index_meta")
    conn.execute(SCHEMA_EPISODES)
    conn.execute(SCHEMA_SKIPPED)
    conn.execute(SCHEMA_META)
    conn.executemany(
        "INSERT OR REPLACE INTO skipped_sources(source_path, source_mtime, source_size) VALUES (?, ?, ?)",
        [(p, *sources[p]) for p in skipped],
    )
    conn.commit()

    t0 = time.time()
    print(f"Inserting {len(episodes)} episodes...")
    conn.executemany(INSERT_SQL, episodes)
    conn.commit()
    print(f"  Insert: {time.time() - t0:.1f}s")

    t0 = time.time()
    print("Creating indexes...")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_episodes_published ON episodes(episode_published_on DESC)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_episodes_podcast ON episodes(podcast_title)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_episodes_type ON episodes(episode_type)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_episodes_duration ON episodes(episode_duration)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_episodes_source ON episodes(source_path)")
    conn.commit()
    print(f"  Indexes: {time.time() - t0:.1f}s")

    # Build FTS index after all rows are in place — much faster than
    # inserting into FTS incrementally or rebuilding at the end.
    t0 = time.time()
    print("Building FTS index...")
    conn.execute(SCHEMA_FTS)
    conn.execute("INSERT INTO episodes_fts(episodes_fts) VALUES('rebuild')")
    # With the rebuild, not after it: the marker is what says the index in this
    # database was actually filled.
    conn.execute("INSERT OR REPLACE INTO index_meta(key, value) VALUES ('fts_built', '1')")
    conn.commit()
    print(f"  FTS build: {time.time() - t0:.1f}s")
    print("Done")


def main():
    parser = argparse.ArgumentParser(description="Index podcast transcripts into SQLite FTS5")
    parser.add_argument("data_dir", help="Path to the podcast data directory")
    parser.add_argument("--db", default=None, help="Path to SQLite database (default: <data_dir>/podcasts.db)")
    parser.add_argument(
        "--full",
        action="store_true",
        help="Rebuild both tables from every transcript, instead of reading only what changed",
    )
    args = parser.parse_args()

    if not os.path.isdir(args.data_dir):
        print(f"ERROR: Data directory does not exist: {args.data_dir}", file=sys.stderr)
        sys.exit(1)

    db_path = args.db or os.path.join(args.data_dir, "podcasts.db")
    print(f"Data directory: {args.data_dir}")
    print(f"Database: {db_path}")

    conn = sqlite3.connect(db_path)

    try:
        conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS _fts5_check USING fts5(x)")
        conn.execute("DROP TABLE IF EXISTS _fts5_check")
    except sqlite3.OperationalError:
        print(
            "ERROR: This SQLite build has no FTS5 support. Install pysqlite3: pip install pysqlite3",
            file=sys.stderr,
        )
        conn.close()
        sys.exit(1)

    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA foreign_keys=ON")

    t0 = time.time()
    sources, unknown = scan_sources(args.data_dir)
    print(f"Found {len(sources)} transcripts in {time.time() - t0:.1f}s")

    # The same guard a full rebuild has always had, and it matters more here:
    # an unmounted share reads as an empty corpus, and an incremental run would
    # take that as every episode having been deleted.
    if not sources:
        print("Nothing to index; leaving the existing database untouched")
        conn.close()
        return

    if not args.full and schema_is_current(conn):
        if run_incremental(conn, args.data_dir, sources, unknown, db_path):
            conn.close()
            return
        print("Some rows predate change tracking; rebuilding in full")
    elif not args.full:
        print("This database has no change tracking yet; building it in full")

    run_full(conn, args.data_dir, sources, unknown, db_path)
    conn.close()


if __name__ == "__main__":
    main()

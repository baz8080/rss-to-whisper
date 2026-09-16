# Ad-skip segment survey

Survey for the separate ad-classification/skip project: does this corpus (or its
feeds) carry per-episode segment boundaries, and are any of them ad-labelled? No
pipeline behaviour changed and no fields were added to `transcript.json` — this is
data-gathering only.

Corpus: 44 feeds from the pipeline's podcast config, 17,578 downloaded episodes,
18,674 episodes across full feed histories (some episodes age out of feeds before
download).

Prior constraints (established before this survey, still true): no podcast spec has
a field marking an ad — a tag for it was proposed and rejected because ad blockers
would consume it. Where ad labelling exists it's incidental. Chapters live in four
places: inline `psc:` markup in the RSS item, ID3 `CHAP` frames in the MP3, a
`podcast:chapters` JSON link, and `podcast:soundbite`. Feed chapter timestamps
describe the unstitched master and drift under dynamic ad insertion; ID3 chapters in
the downloaded file describe that exact file and are the ones to trust for timing.

Which specific shows carry which signal, and this environment's local paths, are
deliberately left out of this doc — see `notes/` (gitignored, not part of the repo)
for the per-show breakdown.

## Method

Two pipeline CLI flags, both read-only, neither touches the whisper server:

```
./transcribe --dump-audio-chapters <data-dir> --dump-limit <n>   # ID3 CHAP frames
./transcribe --dump-feed-markup <feed-url> --dump-limit <n>      # unclaimed RSS elements
```

`--dump-feed-markup` only shows elements ROME's registered modules don't claim —
see [Tool gap](#tool-gap-psc-is-invisible-to-dump-feed-markup) below, it misses one
whole category of chapter data by design. To check for that, and for plain-text
timestamp lists in show notes (also outside the tool's scope: it never reads
`<description>`/`<content:encoded>`), every feed was additionally downloaded and
grepped directly:

```
curl -sL -A "Mozilla/5.0 (compatible; rss-to-whisper-survey/1.0)" <feed-url> -o feed.xml
```

## Findings by source

### 1. ID3 `CHAP` frames (downloaded file — trust this one for timing)

395 / 17,578 episodes (2.2%) carry chapters, almost entirely one show. Two other
shows carry it on one or two episodes each, with content titles only.

That one show's recurring chapter titles (2+ uses) are almost all structural —
intro/start/outro, a recurring "current obsessions" segment, a vote/results
segment, a wrap-up segment — plus one clearly ad-like title (a Patreon/support
plug, 28 uses) and one self-promotion title (a newsletter plug, 8 uses). Below that
is a long tail of one-off episode-specific content titles. That support/Patreon
title is the only one in the entire ID3 corpus that reads as a sponsor marker.

### 2. `libsyn:ad-marker` (feed-level, ad-only — no content labels)

```xml
<libsyn:ad-markers>
  <libsyn:ad-marker type="pre" count="2">
  <libsyn:ad-marker type="mid" count="2" timestamp="1244">
  <libsyn:ad-marker type="post" count="3" timestamp="13926">
</libsyn:ad-markers>
```

`type` (pre/mid/post) + `timestamp` (seconds into the item, i.e. feed/master time,
not the downloaded file) gives exact ad-break starts; `count` is spots at that
break. This is Libsyn's own ad-insertion metadata — purpose-built, not incidental —
currently dropped entirely by the pipeline (shows up as foreign markup, unread by
any code). Checked full history on every Libsyn-hosted or Libsyn-`show-id`-carrying
feed in the config (6 shows): 2 carry it on 100% of episodes and are still active
on the newest episode; 2 carried it for a defined historical window (each turned on
mid-2024 and later switched off, one with gaps inside the window) and are inactive
now; 2 never carry it at all.

Swept all 44 feeds at increasing depth (10 → 50 → full history, 18,674 episodes) —
no other feed in the config ever carries `ad-marker`. (That escalating-depth sweep
was needed only because `--dump-feed-markup`'s tally used to cover just the sampled
entries; it now always tallies the full feed and limits only what's printed, so a
single run at any `--dump-limit` gets the same answer a full-history sweep would.)
This is the cleanest ad-only signal found, but reliable (100%, currently active) on
only 2 of 44 shows, and even where present it isn't a permanent show-level property
— two shows had it turned on and later off within the survey window, so a consumer
needs to check per-episode, not cache a per-show capability flag.

### 3. `psc:chapters` (feed XML — content only, and only one show uses it)

Podlove Simple Chapters. Declared (`xmlns:psc="http://podlove.org/simple-chapters"`)
by 7 feeds as boilerplate but only actually *used* by one, on a small minority of
its episodes. Most of those are a spinoff/compilation format already excluded by
this pipeline's per-podcast `excludes:` filter; the couple of episodes that aren't
excluded are the same two multi-part compilation episodes already covered by ID3,
above. The other 6 feeds declare the namespace — an Anchor/Omny feed-template
artifact — but never emit a `<psc:chapter>`.

Titles match ID3 exactly where both exist for the same episode; timestamps don't.
Measured on one such episode — drift grows roughly linearly through the episode,
~5min added at each internal chapter boundary, consistent with dynamic ad insertion
accumulating in the stitched file that the feed's master timestamps don't see: the
gap between the feed's chapter start and the actual (ID3) start is already about
three minutes by the first internal chapter boundary, growing past 20 minutes by
the last one, in a ~3.5 hour episode -- even the opening segment isn't safe to time
from the feed alone.

Net: confirms the "trust ID3, not feed chapters" rule empirically, but adds no new
coverage — everything usable here is already available, and better, via ID3.

No feed anywhere emits `podcast:chapters` (the Podcasting 2.0 JSON-chapters link) —
checked exhaustively across full feed histories, all 18,674 episodes, zero hits.
Unlike `psc:`, that namespace genuinely isn't claimed by any ROME module (see
[below](#is-there-a-newer-rome-that-fixes-this)), so this negative is trustworthy
rather than a tool blind spot, and since it's zero there's no `chapters.json` URL to
follow — the "would need an extra network call to fetch the linked JSON" caveat that
applies to this tag in general turns out moot for this corpus specifically.
`podcast:soundbite` appears a handful of times, all on one show — a promotional
clip pointer, unrelated to ads. `podcast:transcript` (thousands of uses, on a
handful of shows) is just a link to a transcript file, not a segment marker.

### 4. Plain-text timestamped chapter lists in show notes

Never visible to `--dump-feed-markup` — it only ever prints unclaimed XML markup,
never `<description>`/`<content:encoded>` text. Found by reading raw feed XML
directly. Two formats in use, `(HH:MM) Label` with or without a `Chapters:` /
`📋 Episode Chapters` heading. Two shows have it on the large majority of episodes
(81–90%), with real per-segment topic titles and thousands of entries between them
— but their sponsor reads sit in a separate, un-timestamped "brought to you by"
block, so this source essentially never marks the ad itself. Two other shows have
it on a small minority of episodes (under 10%) and do occasionally timestamp the
sponsor mention directly — but only once each, out of several hundred entries per
show — too rare to build a detector on.

(Matching against brand names as an ad signal throws false positives: shows
discussing a company as an interview topic are indistinguishable from a sponsor
mention by brand name alone. Only phrase-level matches — "sponsor", "brought to you
by", "promo code", "% off" — were counted above.)

## Tool gap: `psc:` is invisible to `--dump-feed-markup`

`--dump-feed-markup` (`pipeline/.../feed/FeedMarkup.kt`) prints `SyndFeed`/`SyndEntry`
`foreignMarkup` — elements ROME's registered parsers didn't claim. `rome-modules
2.1.0` registers `PodloveSimpleChapterParser` for the `psc:` namespace
(`http://podlove.org/simple-chapters`), so `<psc:chapters>`/`<psc:chapter>` *is*
parsed — into a module object nothing in this codebase reads — and therefore never
appears as foreign markup, at any `--dump-limit`. This is why the first pass of
this survey reported zero `psc:` usage anywhere — a false negative from the tool,
corrected in [§3](#3-pscchapters-feed-xml--content-only-and-only-one-show-uses-it)
by grepping raw feed XML instead. Anything built on `--dump-feed-markup` going
forward should know this and check `psc:` separately if it matters. (The doc
comment on `feedMarkupReport` has been corrected to say this; `FeedMarkupTest.kt`
now has a regression test asserting `psc:chapters` stays invisible while
`podcast:chapters` stays visible, so this doesn't silently drift back out of date.)

### Is there a newer ROME that fixes this?

No, and it wouldn't need fixing on the `psc:` side anyway.

`2.1.0` (Mar 2023) is still the latest release — confirmed via `gh api
repos/rometools/rome/releases` and `gh api repos/rometools/rome/compare/2.1.0...master`,
which shows only CI/build/README changes ahead of the tag, no functional code. The
project has had no real commits since June 2023; treat it as dormant, not as
something a version bump will improve.

`psc:` chapters were never actually unreachable — `PodloveSimpleChapterModule`
(already present in 2.1.0) exposes a clean typed API the pipeline just doesn't call:

```java
public interface PodloveSimpleChapterModule extends Module {
    List<SimpleChapter> getChapters();   // SimpleChapter: getStart(), getTitle(), getHref(), getImage()
}
```

i.e. `entry.getModule("http://podlove.org/simple-chapters")` cast to
`PodloveSimpleChapterModule`, then `.getChapters()`, gets structured chapter data
today, no dependency change needed — `FeedMarkup.kt` just never asks for it.

Podcasting 2.0 (`podcast:` namespace: `chapters`, `transcript`, `soundbite`,
`funding`, ...) is a different story: ROME has no module for it at any version.
Every namespace module that exists in the 2.1.0 tree: `itunes`, `psc`, `atom`,
`content`, `mediarss`, `feedburner`, `feedpress`, `fyyd`, `georss`, `slash`, `sse`,
`thr`, `cc`, `sle`, `yahooweather`, `activitystreams`, `opensearch`,
`portablecontacts`, `photocast` — no `podcast`. That's consistent with the survey:
every `podcast:*` element found showed up correctly as foreign markup, because
there's genuinely nothing claiming that namespace to hide it. A future ROME release
could in principle add one, but as of writing there's nothing to upgrade to, and
given the release cadence above, nothing to wait for either — hand-parsing the
handful of `podcast:*` elements this corpus actually uses (walking `foreignMarkup`,
as the tool already does) is the realistic path if any of them become worth
reading.

## Bottom line

Across 44 feeds / ~18,600 episodes, structured or semi-structured segment data
worth anything is concentrated in five shows total:

- **Ad boundaries**, reliably: 2 shows, via `libsyn:ad-marker` — 100% coverage,
  currently active, explicit pre/mid/post + timestamp. Everything else ad-related
  is either one recurring chapter title on one show, or single-digit incidental
  mentions on two others — not enough to classify on.
- **Content boundaries**, reliably: 3 shows — one via ID3 chapters, two via
  plain-text show-note timestamp lists.

Everything else in the config — the other 39 feeds — carries no segment signal of
any kind found by this survey. Not enough breadth to justify a general
`transcript.json` field; enough depth on these five to justify a targeted
enrichment scoped to them specifically, with per-episode (not per-show) presence
checks given `libsyn:ad-marker` has already been observed turning on and off within
a single show's run.

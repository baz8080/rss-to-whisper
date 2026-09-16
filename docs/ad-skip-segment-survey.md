# Ad-skip segment survey

Survey for the separate ad-classification/skip project: does this corpus (or its
feeds) carry per-episode segment boundaries, and are any of them ad-labelled? No
pipeline behaviour changed and no fields were added to `transcript.json` — this is
data-gathering only, run on `claude/episode-segments-ad-skip-m39f2i`.

Corpus: 44 feeds from `podsm5m.yaml`, 17,578 downloaded episodes, 18,674 episodes
across full feed histories (some episodes age out of feeds before download).

Prior constraints (established before this survey, still true): no podcast spec has
a field marking an ad — a tag for it was proposed and rejected because ad blockers
would consume it. Where ad labelling exists it's incidental. Chapters live in four
places: inline `psc:` markup in the RSS item, ID3 `CHAP` frames in the MP3, a
`podcast:chapters` JSON link, and `podcast:soundbite`. Feed chapter timestamps
describe the unstitched master and drift under dynamic ad insertion; ID3 chapters in
the downloaded file describe that exact file and are the ones to trust for timing.

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

395 / 17,578 episodes (2.2%) carry chapters, almost entirely one show:

| Show | Episodes | Content titles | Ad-like titles |
|---|---|---|---|
| Universe Today | 392 | yes | `Support us on Patreon` (28×) |
| Behind the Bastards | 2 | yes | none |
| Lenny's Podcast | 1 | yes | none |

Universe Today's recurring titles (2+ uses): `Intro` (284), `Start` (104), `Outro`
(91), `Current obsessions` (68), `Vote results` (63), `Final thoughts` (41), `Final
thoughts and more interviews` (26), `Support us on Patreon` (28), `More space
news`/`What's next` (18 each), `More interviews`/`Vote` (11 each), `Interviews`
(10), `Newsletter` (8, self-promo not third-party ad), `Guide to Space` (6+4).
Everything below that is a long tail of one-off episode-specific content titles.
`Support us on Patreon` is the only title in the entire ID3 corpus that reads as a
sponsor/support marker.

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
feed in the config:

| Show | Coverage | Status |
|---|---|---|
| Citation Needed | 495/495 (100%) | active on the newest episode |
| Sean Carroll Mindscape | 444/444 (100%) | active on the newest episode |
| Walkabout the Galaxy | 55/376 (14.6%) | one unbroken run, Jul 2024 → Feb 2026, then stopped |
| Astronomy Cast | 27/567 (4.8%) | Jul 2024 → Jun 2026 with 8 gaps inside, then stopped |
| Ask a Spaceman | 0/285 | never |
| Titanium Physicists | 0/96 | never |

Swept all 44 feeds at increasing depth (10 → 50 → full history, 18,674 episodes) —
no other feed in the config ever carries `ad-marker`. This is the cleanest ad-only
signal found, but reliable (100%, currently active) on only 2 of 44 shows, and even
where present it isn't a permanent show-level property — Walkabout and Astronomy
Cast both had it turned on mid-2024 and off again, so a consumer needs to check
per-episode, not cache a per-show capability flag.

### 3. `psc:chapters` (feed XML — content only, and only one show uses it)

Podlove Simple Chapters. Declared (`xmlns:psc="http://podlove.org/simple-chapters"`)
by 7 feeds as boilerplate but only actually *used* by one:

- **Behind the Bastards** — 515 `<psc:chapter start="HH:MM:SS" title="...">`
  elements across 88 episodes. 86 of those are "It Could Happen Here Weekly"
  spinoff episodes, already excluded by `pods.yaml`'s `excludes:` list. The
  remaining 2 (`The Darién Gap: Where Dreams Die`, `Anti-Vax America: The Complete
  Series`) are the same two compilation episodes already covered by ID3, above.
- The other 6 (irish folklore podcast, unreal irish folklore, Gravity Assist,
  Planetary Radio, Daniel and Jorge Explain the Universe, Why This Universe)
  declare the namespace — an Anchor/Omny feed-template artifact — but never emit a
  `<psc:chapter>`.

Titles match ID3 exactly where both exist for the same episode; timestamps don't.
Measured on Darién Gap — drift grows roughly linearly through the episode, ~5min
added at each internal chapter boundary, consistent with dynamic ad insertion
accumulating in the stitched file that the feed's master timestamps don't see:

| Chapter | ID3 (actual file) | feed `psc:` (master) | drift |
|---|---|---|---|
| Migration Through the Darién Gap | 0:03:25 | 0:00:24 | +3m01s |
| Emberá Community Welcomes Migrants | 0:49:37 | 0:41:38 | +7m59s |
| What Migrants Leave Behind | 1:26:47 | 1:13:44 | +13m03s |
| The Migrant Reception Center | 2:12:10 | 1:54:03 | +18m07s |
| Mutual Aid Along the Migrant Journey | 2:58:26 | 2:35:21 | +23m05s |

Net: confirms the "trust ID3, not feed chapters" rule empirically, but adds no new
coverage — everything usable here is already available, and better, via ID3.

No feed anywhere emits `podcast:chapters` (the Podcasting 2.0 JSON-chapters link) —
checked exhaustively across full feed histories, all 18,674 episodes, zero hits.
Unlike `psc:`, that namespace genuinely isn't claimed by any ROME module (see
[below](#is-there-a-newer-rome-that-fixes-this)), so this negative is trustworthy
rather than a tool blind spot, and since it's zero there's no `chapters.json` URL to
follow — the "would need an extra network call to fetch the linked JSON" caveat that
applies to this tag in general turns out moot for this corpus specifically.
`podcast:soundbite` appears 4 times, all on *Why This Universe* — a promotional clip
pointer, unrelated to ads. `podcast:transcript` (6,693 uses, Behind the Bastards /
Conversations at the Perimeter / Daniel and Jorge / Spacetime Pod / Space Nuts) is
just a link to a transcript file, not a segment marker.

### 4. Plain-text timestamped chapter lists in show notes

Never visible to `--dump-feed-markup` — it only ever prints unclaimed XML markup,
never `<description>`/`<content:encoded>` text. Found by reading raw feed XML
directly. Two formats in use, `(HH:MM) Label` with or without a `Chapters:` /
`📋 Episode Chapters` heading:

| Show | Coverage | Entries scanned | Genuine ad-labelled entries |
|---|---|---|---|
| Lenny's Podcast | 326/361 (90%) | 6,386 | 0 |
| Pragmatic Engineer | 60/74 (81%) | 1,247 | 0 |
| Space Nuts | 53/667 (8%) | 338 | 1 — `(14:30) Stay safe online with our sponsor, NordVPN` |
| Spacetime Pod | 57/1000 (6%) | 358 | 1 — `(00:12:52) Space Time is brought to you by Squarespace` |

Lenny's and Pragmatic Engineer are a genuinely rich **content** topic-boundary
source — real per-segment topic titles on the large majority of episodes — but
their sponsor reads sit in a separate, un-timestamped "Brought to you by:" block,
so this source essentially never marks the ad itself. Space Nuts and Spacetime Pod
do occasionally timestamp the sponsor mention, but at roughly 1 in several hundred
episodes — too rare to build a detector on.

(Matching against brand names as an ad signal — "Shopify", "NordVPN", etc. — throws
false positives: Lenny's and Pragmatic Engineer both interview Shopify staff and
discuss Shopify as a *topic*, indistinguishable from a Shopify sponsor mention by
keyword alone. Only phrase-level matches — "sponsor", "brought to you by", "promo
code", "% off" — were counted above.)

## Tool gap: `psc:` is invisible to `--dump-feed-markup`

`--dump-feed-markup` (`pipeline/.../feed/FeedMarkup.kt`) prints `SyndFeed`/`SyndEntry`
`foreignMarkup` — elements ROME's registered parsers didn't claim. `rome-modules
2.1.0` registers `PodloveSimpleChapterParser` for the `psc:` namespace
(`http://podlove.org/simple-chapters`), so `<psc:chapters>`/`<psc:chapter>` *is*
parsed — into a module object nothing in this codebase reads — and therefore never
appears as foreign markup, at any `--dump-limit`. The tool's own doc comment ("rome-
modules registers nothing for the Podcasting 2.0 namespace") is accurate for
`podcast:chapters` but not for `psc:`, a different, older namespace. This is why the
first pass of this survey reported zero `psc:` usage anywhere — a false negative
from the tool, corrected in [§3](#3-pscchapters-feed-xml--content-only-and-only-one-show-uses-it)
by grepping raw feed XML instead. Anything built on `--dump-feed-markup` going
forward should know this and check `psc:` separately if it matters.

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
every `podcast:*` element found (`transcript`, `locked`, `soundbite`, `funding`,
`guid`, `podping`, `txt`) showed up correctly as foreign markup, because there's
genuinely nothing claiming that namespace to hide it. A future ROME release could in
principle add one, but as of writing there's nothing to upgrade to, and given the
release cadence above, nothing to wait for either — hand-parsing the handful of
`podcast:*` elements this corpus actually uses (walking `foreignMarkup`, as the tool
already does) is the realistic path if any of them become worth reading.

## Bottom line

Across 44 feeds / ~18,600 episodes, structured or semi-structured segment data
worth anything is concentrated in five shows:

- **Ad boundaries**, reliably: **Citation Needed**, **Sean Carroll Mindscape**
  (`libsyn:ad-marker`, 100% coverage, currently active, explicit pre/mid/post +
  timestamp). Everything else ad-related is either one recurring chapter title
  (`Support us on Patreon` on Universe Today) or single-digit incidental mentions
  (Space Nuts, Spacetime Pod) — not enough to classify on.
- **Content boundaries**, reliably: **Universe Today** (ID3, 392 episodes),
  **Lenny's Podcast** (show notes, 326 episodes), **Pragmatic Engineer** (show
  notes, 60 episodes).

Everything else in the config — the other 39 feeds — carries no segment signal of
any kind found by this survey. Not enough breadth to justify a general
`transcript.json` field; enough depth on these five to justify a targeted
enrichment scoped to them specifically, with per-episode (not per-show) presence
checks given `libsyn:ad-marker` has already been observed turning on and off within
a single show's run.

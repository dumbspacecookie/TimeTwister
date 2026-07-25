# TimeTwister eval harness

The tests in `TimeConverterTest` / `SpliceTest` are hand-picked examples: they prove
that a handful of inputs work. This directory holds the other thing — a **graded
corpus** that measures the parser as a classifier and as a transformer, so a change
that quietly widens the false-positive surface shows up as a number moving instead
of as nobody noticing.

| file | what it is |
| --- | --- |
| `corpus.tsv` | 214 graded rows: input, whether it *should* be detected, and the exact string the rewrite *should* produce |
| `../../kotlin/com/timetwister/core/EvalCorpusTest.kt` | loads the corpus, scores it, prints the report, gates the build |
| `../../kotlin/com/timetwister/core/PropertyTest.kt` | 9 invariants checked against seeded generated input |

---

## Running it

From the `android/` directory:

```bash
# everything, with the eval report on stdout
./gradlew :core:test -i

# just the eval
./gradlew :core:test --tests 'com.timetwister.core.EvalCorpusTest' -i

# just the properties
./gradlew :core:test --tests 'com.timetwister.core.PropertyTest' -i

# replay a red property run exactly
./gradlew :core:test --tests 'com.timetwister.core.PropertyTest' -Dtimetwister.eval.seed=12345 -i
```

On Windows use `gradlew.bat` (or `.\gradlew`) instead of `./gradlew`.

**The `-i` matters.** Gradle swallows test stdout by default, and the report is
printed to stdout. Without `-i` you get pass/fail but no score. The report is also
always in the HTML report at `core/build/reports/tests/test/index.html` — open the
test and expand *Standard output*.

If you want the score on every run without `-i`, add this to `core/build.gradle.kts`
(deliberately not done here, so this harness touches no existing file):

```kotlin
tasks.test {
    testLogging { showStandardStreams = true }
}
```

---

## Reading the score

```
DETECT DECISION (positive class = "this text contains a time")
                    all rows        blocking rows only
  true positives  ...
  false positives ...              <- each one is a visibly broken message
  ...
  precision       ...
  recall          ...
  F1              ...

REWRITE (exact string match on TimeConverter.splice)
  exact match     ...
  row pass        ...              <- the gated number
```

* **precision** — of the strings we rewrote, how many *should* have been rewritten.
  This is the number that matters for a text-replacement tool. A false positive is
  not a missed feature, it is a user's message with garbage spliced into the middle
  of it (`won 3-1 pt` → `won 3-1am PT (3am CT ...)`). Precision should be the last
  metric you trade away.
* **recall** — of the times a user actually wrote, how many we caught. Cheap to
  improve, cheap to lose; a miss just means the tool did nothing.
* **exact match** — the rewritten string is byte-identical to `expect_output`.
  Stricter than the detect decision: it also covers zone maths, DST, label choice,
  the `·` separator and where the splice boundaries land.
* **row pass** — detect decision *and* exact string both correct. Restricted to
  blocking rows, this is what gates the build.

A row is graded against the fixed clock, never the wall clock:

| pin | value |
| --- | --- |
| `now` | `2026-05-18T09:00:00-04:00[America/New_York]` |
| targets | `America/Chicago`, `America/New_York`, `America/Los_Angeles`, `Europe/London` → rendered `CT`, `ET`, `PT`, `UK` |
| system default zone | pinned to `America/New_York` for the duration of the class |

That last pin exists because `TimeParser.detect()` calls `ZoneId.systemDefault()`
for expressions with no zone token, and that seam is not injectable. About 20 rows
depend on it; the corpus header lists them by id. If you ever make the default zone
injectable, delete the pin and pass it explicitly — it is the one piece of global
state this harness has to touch.

---

## Categories, and what can fail the build

Blocking — a mismatch fails `:core:test`:

| category | holds |
| --- | --- |
| `must_detect` | happy paths, every alias token, 12h/24h, minutes, noon/midnight, half-hour zones |
| `must_not_detect` | the false-positive suite — the valuable half |
| `whitespace` | separator and padding variants |
| `unicode` | emoji / RTL / non-Latin, pinning UTF-16 splice indices |
| `boundary` | out-of-range hours and minutes |
| `idempotent` | a second pass must not change the text again |

Reported only — scored, printed, never fatal:

| category | meaning |
| --- | --- |
| `known_gap` | **desired** behaviour the code does not produce today. A real defect, written down so it shows in the score instead of being forgotten. |
| `ambiguous` | the right answer is genuinely debatable. `expect_output` is a *proposal*; the reasoning is in `note`. Needs an owner decision. |
| `unverified` | expected value derived by reading the source but never confirmed on a real JVM. Promote (or fix) after the first green run. |

The split is the point: CI stays green and honest at the same time. Open questions
live in the corpus rather than in someone's head, and the report prints every one of
them on every run.

---

## Adding a row

Append a line to `corpus.tsv`. Six tab-separated columns, no tabs inside a field:

```
id <TAB> input <TAB> expect_detect <TAB> expect_output <TAB> category <TAB> note
```

* **id** — stable, never renumbered. Prefixes in use: `D` detect, `Z` alias coverage,
  `W` whitespace, `N` not-detect, `B` boundary, `U` unicode, `I` idempotency,
  `G` known gap, `A` ambiguous, `V` unverified. Just append the next free number.
* **expect_detect** — `yes` / `no`. What `TimeParser.detect(input).isNotEmpty()`
  *should* return.
* **expect_output** — the exact full string `TimeConverter.splice` *should* return,
  or the literal token `=input` when nothing should change. If `expect_detect` is
  `no` then `expect_output` must be `=input`; the loader enforces it.
* **note** — always fill it in. For `ambiguous` and `unverified` rows this is the
  argument, and it is what the owner reads when adjudicating.

Escapes, decoded by the loader:

| write | get |
| --- | --- |
| `\t` `\n` `\r` | tab, newline, carriage return |
| `\\` | a literal backslash |
| `\uXXXX` | that UTF-16 code unit |
| `\uD83C\uDF89` | an astral character, written as its two UTF-16 surrogates: 🎉 |
| `\u0020` | a space at the very start or end of a field, so no editor can eat it |

Lines starting with `#` are comments; blank lines are ignored.

**The file must stay UTF-8 without a BOM.** Expected outputs contain `·`
(U+00B7 MIDDLE DOT), the separator the renderer emits. The loader fails fast with an
explicit message if it sees U+FFFD, which is what a cp1252 round-trip leaves behind.

### Do not guess an expected value

Derive it from the source or from a real run. If you cannot, file the row as
`category=unverified` with your best guess and say so in the note — that is what the
category is for. A wrong expectation in a blocking row is worse than no row: it
becomes gospel the moment someone "fixes" the code to match it.

---

## Ratcheting

**Corpus** — `EvalCorpusTest.MIN_BLOCKING_PASS_RATE` (currently `1.0`) is the minimum
fraction of blocking rows that must pass exactly.

The healthy loop is:

1. Fix a defect described by a `known_gap` row.
2. Move that row into its real category (`must_not_detect`, `idempotent`, …) and
   update `expect_output` if the fix landed somewhere different.
3. It is now enforced for free. The gate never has to move.

Do **not** lower `MIN_BLOCKING_PASS_RATE` to land a change. If a change knowingly
regresses a blocking row, move that row to `known_gap` with a note saying why — the
defect stays in the score, and the gate keeps protecting everything else. Lowering
the gate hides the whole suite at once.

**Properties** — `PropertyTest.ENFORCED_PROPERTIES` is the same idea as a set. Two
properties sit outside it today because the implementation violates them:

* `idempotent` — `splice(splice(x)) != splice(x)`. The rendered stamp contains times
  the parser happily re-detects, so a second pass stamps the stamp.
* `labels-reparse` — `AK`, `HI`, `UK`, `NZ`, `CN` are rendered as labels but are not
  alias tokens, so the tool cannot read back a string it just wrote.

Move a name into `ENFORCED_PROPERTIES` when the fix lands. Never delete a property
to make the build green: the reported list is the record of what is still broken.

Every property prints its seed. A red run is replayed verbatim with
`-Dtimetwister.eval.seed=<the printed seed>`.

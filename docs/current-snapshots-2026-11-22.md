# V3 on current development snapshots — 2026-11-22

This note records the development gate after V3 caught up with the current Client,
Journal and Experiment snapshots. It adds to the [frozen tested baseline](current-baseline.md)
and does not replace it. That record still describes the 2026-11-01 closure. The frozen
closure in its isolated Maven cache remains the evidence for every past run. No result,
dataset, figure, acquisition or frozen closure was changed, and no experiment was repeated.

## Gate

Offline, against the ordinary `~/.m2` development snapshots:

```sh
./mvnw -o -B -ntp clean verify
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v
```

- Java: **38 tests, 0 failures, 0 errors, 0 skipped**. These are the 35 existing tests, plus
  two new ones in `PreSchemaFiveTest`, plus a Workflow-attempt case of the Codex admission
  test. Surefire runs the test JVM with `-Xmx1g`.
- Python: **45 tests, OK**. These are the 44 existing tests plus one schema 5 mapping test.

No provider CLI or model was called. The Codex and Claude children are the checked-in
deterministic fixtures.

## Upstream bytes the gate ran against

The POM coordinates are unchanged. These are the `-SNAPSHOT` JARs Maven resolved from
`~/.m2`. Each was installed after the commit named here. Any later commit on that main
changes only documentation. Matching coordinates alone do not prove matching bytes.
For Client and Journal the installed JARs were checked to contain the named commit's
source change. Upstream suites were not rerun by V3.

| Producer | Coordinate | Source commit (origin/main) | Installed |
|---|---|---|---|
| Experiment runtime | `0.10.0-SNAPSHOT` | `3d40303435ce02837675038cb3ac78bfc26e309f` (main `daf8fc5` adds release notes only) | 2026-11-20 20:13 |
| Client core/model/Claude/Codex/Journal | `0.32.1-SNAPSHOT` | `96ede5db` (main `bb07fe4e` adds agent instructions only) | 2026-11-17 14:23 |
| Journal writer/TCK | `2.0.0-SNAPSHOT` | `b0db3fe68e6daf87cae674904ad287500647e626` | 2026-11-19 14:29 |
| Workflow (via Experiment) | `1.1.0-SNAPSHOT` | `62a40cd` (main `785d4b6` documentation only) | 2026-11-17 13:07 |
| Judge core (via Experiment) | `1.1.0-SNAPSHOT` | `7df7cde` (main `9e18aef` documentation only) | 2026-11-17 13:05 |
| Claude SDK (via Client) | `1.9.0-SNAPSHOT` | `9cfdb7f6` (main `08ab1210` documentation only) | 2026-11-17 12:51 |
| Judge exec | released `0.18.0` | unchanged; Experiment's verdict codec accepts `agent-judge-v6:0.18.0` only | — |

## What changed in V3

1. **Client provider packages** (Client `f3772d02`). The providers moved to
   `agents.client.claude` and `agents.client.codex`. `ClaudeAcquisition` and the other model
   types stay in `agents.claude`. The installed-reader guard in `scripts/ReadNativeCoverage.java`
   now names the moved Codex provider, so it still refuses a reader classpath that contains it.
2. **Native recording outcome** (Client `b2f4da94`, Experiment `a2b72f8`). The Codex recorder
   now takes Client's sealed `NativeGeneration` and hands it to Client's
   `JournalExecutionRecorder`. Journal qualifies the generation with its qualifier for the
   acquisition profile, and Client delivers one binding and reports the `NativeRecording`
   outcome. V3 no longer qualifies or assembles the binding by hand. It still writes its own
   `codex-original-acquisition-reference.json` and keeps `codex-archive-failure.txt` for
   delivery failures. A wrong capture now delivers an evidence-only binding reported as
   `REJECTED`. Before, the delivery failed. Either way, admission is refused. The Claude route
   already used Client's own recorder, which takes native generations, so it needed no change.
3. **Capture limit** (Client `84c17d9f`). The overflow fixture now writes 256 MiB plus 64 KiB of
   stderr, which really exceeds the current 256 MiB limit. The test asserts the capture is
   incomplete and names the limit and the dropped bytes. It takes about 1.6 s and needs about
   320 MiB of heap, so the POM sets surefire's `argLine` to `-Xmx1g` rather than relying on
   the JVM's default of a quarter of RAM.
4. **Experiment runtime schema 5** (Experiment `3d40303`).
   - `PilotRevision` reads its parents with `FileSystemResultStore.read`, so a schema 4 parent
     keeps its meaning and version.
   - It writes the revision at `CURRENT_SCHEMA_VERSION` and compares the row's execution kind
     and state.
   - `NativeCoverageApp.readInvoked` reads a retained pre-schema-5 `invoked.json` through
     Experiment's public schema 4 `Attempt` constructor. It stays a Workflow attempt with its
     original coordinates, and nothing is added.
   - Tests that start Client directly use `Attempt.direct`. The Codex admission test also runs
     with a Workflow attempt shaped like the production route's, so the Workflow branch of
     the recorded correlation stays covered.
   - The Python mapping and acceptance readers accept both schema 4 and schema 5 Workflow rows.
     They refuse direct rows.

## Fixture provenance

The fixtures in `src/test/resources/pre-schema5/` were written by Experiment runtime
`91817ec697c09e3bd20fbb2826b1a2a24c11da40` (schema 4, the commit before schema 5). They were
generated by `GeneratePreSchema5.java` in that directory, which is not compiled by the build:

1. `git archive 91817ec` from agent-experiment, packaged offline in a scratch directory with
   `-pl experiment-runtime -DskipTests package`. The JAR SHA-256 was
   `1766c4cca9b15a32a33af5b322af793461a7f244dbdb760f81ec8d1655bcf6e5`. Nothing was installed.
2. The generator ran from the V3 project root. Its classpath held that JAR in place of the
   current runtime, plus V3's compiled classes and other dependencies. The results were saved
   by the 91817ec `FileSystemResultStore`, and `invoked.json` by V3's `CoverageEvidence` writer.
   The assessment verdict is copied unchanged from Experiment's schema 4 fixture
   `experiment-runtime/src/test/resources/schema4/workflow-succeeded.json`. That file is not
   in 91817ec. Experiment `a8014cc` checked it in, and its README says the 91817ec store
   wrote it. The generator therefore reads it from a current Experiment checkout, while the
   writer on its classpath is the 91817ec JAR.

The commands, from the V3 project root, with `$OLD` the packaged 91817ec JAR and `$CP` V3's
`dependency:build-classpath` without its current `experiment-runtime` entry:

```sh
javac -d "$GEN" -cp "$OLD:target/classes:$CP" src/test/resources/pre-schema5/GeneratePreSchema5.java
java -cp "$GEN:$OLD:target/classes:$CP" io.tuvium.experiment.coverage.GeneratePreSchema5 \
  /path/to/current/agent-experiment/experiment-runtime/src/test/resources/schema4/workflow-succeeded.json
```

The snapshot and acquisition manifest are synthetic and say so. FileRefs are relative to
the project root, which is the test working directory.

| File | SHA-256 |
|---|---|
| `parent-store/cGlsb3Qtc2NoZW1hNA/run.json` | `d0819632c35f0d1b4726260cffd9b24743109e5cef305d21e526a94f0421ec80` |
| `assessment-store/cGlsb3Qtc2NoZW1hNA/run.json` | `d1d0244a10c174349e9543e5ca361a32d3718c7e8588e443f8df144762fa9570` |
| `invoked.json` | `0600148e0647f21284afdce42c089862f0ee9be3def4012fad109fc486448043` |
| `evidence/sources/acquisition.json` | `de76694cbe81ccd68a0334e8f15ab85a69574f64d4cc9158b750842190b222e9` |
| `evidence/workflow/snapshot.json` | `fa9b0710095a45e363a130b6215714b59821fc5da25575b8de1cc6f877737268` |

## Gaps

- Experiment reads a retained schema 4 `delivery.json` and `outcome.json`, but it has no public
  reader for a retained schema 4 `Invocation` or `Attempt`. `Schema4.upgradeRetainedAttempt`
  is package-private. V3 therefore keeps a small reader of its own.
- No V3 Java test exercises Claude-route native admission end to end. That gap predates this
  change.

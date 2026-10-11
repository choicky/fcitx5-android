# Engineering and review guidance

Keep development lightweight for small, clearly non-behavioral changes. For a
meaningful implementation batch, use the following Change Contract flow:

Change Contract
→ implementation
→ actual diff review
→ negative-diff review
→ compile-risk pass where relevant
→ local validation where available
→ commit/push/PR
→ CI only when required by the Task Contract, release gate, repository rule,
  or explicit user request
→ behavioral ambiguity/change = STOP

## Actual diff review

An implementation report describes intent, but it is not a substitute for
reviewing the actual code. Before approving an important batch for commit,
push, or merge, inspect as appropriate:

- the actual base and HEAD;
- the actual changed-file list;
- the complete relevant diff, including newly created files;
- surrounding source, API, and call-chain context where needed.

If the reviewer has not inspected the actual relevant code and diff, the batch
must not be represented as code-reviewed. This is especially important for
cross-file changes, Android lifecycle or input-window changes, Voice/ASR,
persistence/configuration, Toolbar/editor behavior, drag/drop, and changes to
existing product behavior.

## Negative-diff review

Every meaningful behavioral, UI, or configuration batch must explicitly answer:

> Besides the intended/requested behavior, what else changed?

Review the complete diff for unintended behavior outside the Change Contract.
UI or configuration work must not silently change business logic, runtime
eligibility, persistence semantics, lifecycle, trigger behavior,
provider/backend selection, unrelated settings, or existing keyboard/input
behavior.

If an unintended or unauthorized behavioral delta is found, STOP. Do not
continue to commit, push, or merge merely because the code compiles, tests
pass, CI is green, or the change appears convenient. Resolve the behavioral
decision first.

## Compile-risk pass

When the authoritative Android compile/test toolchain is unavailable locally,
perform an explicit compile-risk review before push. For modified or newly
added Kotlin/Android code, inspect the complete changed expression and its
immediate type/API context, including as applicable:

- Android API and property/method names;
- Kotlin property mappings;
- imports, receiver scope, and extension/DSL availability;
- constructor arguments, overloads, and nullable/non-null types;
- object/Companion/class/instance distinctions;
- View, ViewGroup, and LayoutParams APIs;
- project-specific APIs and expected receiver types;
- API-level and version assumptions.

Do not inspect only the compiler-highlighted token during a compile repair.
This pass reduces avoidable CI round trips on tasks that do require CI, but does
not replace compilation.
Run the authoritative local checks whenever they are available.

## Local validation and APK delivery

Where the Android toolchain is available locally, the authoritative validation
is the real local Gradle build, not CI. Typical local checks:

- `BUILD_ABI=arm64-v8a ./gradlew :app:assembleDebug` for an installable debug
  APK;
- `BUILD_ABI=arm64-v8a ./gradlew :app:testDebugUnitTest` for JVM unit tests;
- targeted compile tasks (for example `:app:compileReleaseKotlin`) when a
  source-set boundary is involved.

Keep local builds scoped and fast; the arm64-v8a single-ABI debug build is the
default for validation and test delivery.

When the change needs real-device verification — keyboard or input-window
behavior, IME lifecycle, long-press, drag/drop, persistence in app settings,
Voice/ASR — compilation and unit tests are not proof. Deliver the built APK to
the user for device verification: give the APK path, its version identifier
and sha256, and state exactly which behaviors to check. An APK delivered for
installation or testing must use the repository's established signing
identity, and the final APK signature must be verified; an unsigned APK is an
intermediate build artifact, not a deliverable, unless the Task Contract
explicitly requests unsigned output. The debug build installs beside the
official app under its own `.debug` applicationId, so a
test APK does not replace the user's regular install. Then STOP and wait for
device results; do not represent an unverified behavior as fixed.

Do not trigger CI merely to obtain a test APK or to repeat a build that
already succeeded locally. When a deliverable APK is built locally and GitHub
delivery is required, upload that exact final signed local artifact; do not
rebuild the same APK in GitHub CI merely to obtain an artifact. The
repository's test and release APK workflows are for CI runs that the Task
Contract, a release gate, or an explicit user request actually requires.

## CI autonomy boundary (mechanical repair, when CI is required)

This boundary applies only to tasks where CI actually runs — that is, when the
Task Contract, a release gate, a repository rule, or an explicit user request
requires CI. It does not mean that ordinary PRs should trigger CI, and CI must
not be used to repeat build/test work that already succeeded locally. Within
that scope, mechanical CI fixes may be handled autonomously. Behavioral fixes
require confirmation.

An implementation agent may diagnose, minimally repair, commit/push, and—only
for a CI run that is actually required—retry CI when all of the following are
true:

- the root cause is concrete from compiler, formatter, linter, or
  test-compilation output;
- the repair is local and mechanical;
- runtime semantics, architecture, lifecycle, persistence/transaction
  semantics, drag/drop semantics, Voice/ASR/provider behavior, and normal
  Toolbar behavior are demonstrably unchanged;
- no new dependency is introduced and scope is not expanded.

Typical autonomous repairs include syntax errors, missing or wrong imports,
unambiguous type/receiver corrections, unambiguous Android/Kotlin
API/property naming corrections, formatting/lint failures, and mechanical
test-source compilation corrections.

For every autonomous repair:

1. Read the actual failure log.
2. Identify the concrete root cause.
3. Make the smallest repair.
4. Inspect the actual repair diff.
5. Perform negative-diff and compile-risk checks as applicable.
6. Run `git diff --check` and available local validation.
7. Commit and push the repair, then inspect the next CI result.

Do not impose an arbitrary retry-count limit. The boundary is the nature of
the change, not the number of CI iterations.

STOP and request review or confirmation if resolving CI would require or might
require runtime, architecture, lifecycle, persistence, transaction,
drag/drop, UI/product, Voice/ASR/provider, or normal Toolbar behavior changes;
a new dependency; scope expansion; or any ambiguity about whether the repair
is mechanical.

### Test rule

A failing test is not automatically a mechanical failure. STOP if the proposed
repair would change expected values, assertions, fixtures, behavioral
coordinates or inputs, semantic test setup, weaken/remove a test, or otherwise
change the test expectation to accommodate the implementation, unless
independent review establishes that the test itself is incorrect.

Do not fix a test merely to make CI green.

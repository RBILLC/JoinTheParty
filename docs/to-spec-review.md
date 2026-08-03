# to-spec review — OSS tempogram · β-PHAT whitening · duck probe · 2026-08-03

**Status: spec-only.** Three sections appended to `technical-requirements.md`
(§2.10 lines 400–461, §2.11 lines 463–508, §2.12 lines 510–574), promoted from
`docs/research-dsp-upgrades.md`. Nothing in this pass touches code, tests, or
build files — that's the next stage (`/to-tickets` → `/implement`). Companion
docs: `research-dsp-upgrades.md` (source), `research-closed-loop-control.md`
(the MHT bank §2.10 seeds), `docs/ctl03-review.md` (style precedent for the
review-doc format, adapted here from "landed" to "spec-only").

## §2.10 — OSS beat-period tracker (autocorrelation tempogram)

**Key constants:** frame `N=1024`/hop `H=512` at 48 kHz → `F_oss=93.75 Hz`;
OSS ring `M=1125` samples (≈12 s); local-mean window `W≈47` (±0.5 s); search
`ℓ ∈ [24,112]` bins (250–1200 ms, 240–50 BPM); `BeatEstimate.stable` = last 3
estimates agree within ±10 ms spanning ≥ 20 s (reuses §2.7's
`confirm_window_ns` idiom rather than a new agreement rule).

**Provisional markers carried forward, as required.** `γ = 100`
(log-compression) and the `0.5` harmonic-sum weight in
`s(ℓ) = r̂(ℓ) + 0.5·r̂(2ℓ)` are both flagged in the spec text as **provisional
pending primary-source retrieval, field-tunable** — Peeters (2007) and
Grosche & Müller (2011) are cited from model knowledge, not retrieved this
session (research doc §5). This is the one place in the three sections where
a numeric default is explicitly *not* asserted as final; every other
constant in §2.10–§2.12 either traces to shipped code (β-PHAT derivation) or
is flagged separately as a proposed/field-tunable value (duck cooldown).

**Deliberate deviation: none.** The spec text matches the research doc's
design faithfully, including the §0.1 correction (no STFT ring exists; this
adds an incremental onset-strength ring off the same post-AEC tap
`append_history` already uses). Two hard limits are restated **verbatim** per
the task brief: the hypothesis bank never touches self-match (CTL-01 owns
it), and nothing consumes `peak_ratio` as evidence anywhere in this feature.

**Conflict found and resolution.** None — §2.10 is wholly new ground; it
doesn't touch `WindowLag`, `analyze_window`, or any existing `PolicyConfig`
field. Its only coupling to existing sections is read-only: the §2.8
cross-check against `WindowLag.second_lag_ms` (unchanged) and the §2.7
`confirm_window_ns` naming idiom (reused, not modified).

## §2.11 — Parameterized whitening exponent (β-PHAT)

**Key constants:** shipped β=0.5 (`|X|¹`, `p = power/(mag+1e-9f)`), proposed
β=0.7 (`|X|^0.6`), rejected β=1.0 (full PHAT, per `lag_window.h`'s own header
comment on single-buffer program material). New trailing defaulted parameter
`analyze_window(..., double whiten_beta = 0.5)`. Corpus sweep β ∈ {0.5, 0.6,
0.7, 0.8} over `docs/sync-test-results.md` + FT8 captures.

**Deliberate deviation: none — and the spec is explicit that the on-device
default is out of scope for this section.** The byte-identical rule is
stated as non-negotiable: at β=0.5 the legacy branch
(`p = power / (mag + 1e-9f)`) runs verbatim, never replaced by a generalized
`pow(power, 0.5)` call, because that is not bit-identical to the shipped
epsilon-guarded division — verified against the actual `lag_window.cpp`
source (`mag = std::sqrt(power) + 1e-9f; p = power / mag;`) before writing
the spec text, not just against the research doc's paraphrase.

**Conflict found and resolution.** `lag_window.h`'s header comment says
"Ported verbatim — do not 'improve' the math here without re-running the
field-test corpus." §2.11 resolves the apparent tension (adding a parameter
*is* touching the file) by making the addition itself corpus-safe: the
parameter is trailing-defaulted to the exact legacy value, the legacy branch
is untouched code, and every non-default behavior is confined to offline
`lag_analyzer --beta` tooling until a *future* spec section — explicitly not
this one — flips the shipped default after the corpus sweep's promotion
criteria (no lag flips/`found` regressions on healthy locks, measurable
reverberant-window gains) are met. This mirrors the precedent §2.8 already
set for `comb_ratio`: additive, corpus-preserving, byte-identical graded
path.

## §2.12 — Volume-duck active probe & capture-energy verdict

**Key constants:** nominal duck 150 ms / −6 dB (achieved dB echoed as a
deci-dB int, since volume-index quantization rarely hits −6.0 exactly); 20 ms
RMS hops → 50 Hz envelope; search window `[echo−250 ms, echo+duck_ms+750 ms]`;
verdict bands `D≥4 dB ∧ z≥3` → self-dominant (`kTrackLost`), `D≤1.5 dB` →
cleared, otherwise inconclusive → escalate once to the shipped pause probe;
z-score normalized by `1.4826·MAD` over the preceding 3 s; proposed duck
cooldown 60 s vs. the pause probe's unchanged 120 s `probe_cooldown_ns`.

**Deliberate deviations, flagged in the spec text itself:**

1. **Duck cooldown (60 s) is proposed, not derived** — the spec text
   explicitly labels it "a proposed value, not a derived one," parallel to
   how `probe_pause_ms` itself was field-tuned rather than derived (§2.9's
   own "Honest marginality note"). No literature formula backs 60 s; it is
   the research doc's suggestion carried through unchanged, and is named as
   a field-tuning knob rather than a fact.
2. **`abi_c_check.c` is explicitly NOT edited by this pass.** The task brief
   and the spec text both call this out: `SC_EVT_ACTIVE_DUCK` doesn't exist
   yet, so adding a `case` for it to the exhaustive switch in
   `core/tests/abi_c_check.c` wouldn't compile until the enum lands. §2.12
   instead names the switch-case addition (plus `sc_evt_active_duck_t` /
   `sc_notify_duck_executed` contract coverage, mirroring the file's existing
   `SC_EVT_ACTIVE_PROBE` coverage) as a **required deliverable of the
   implementing ticket** — the same append-only discipline §2.9 established
   when `SC_EVT_ACTIVE_PROBE` was added, just sequenced one stage later since
   this is spec, not implementation.

**Conflict found and resolution.** The research doc's own §0.3 correction is
load-bearing here: a volume duck doesn't shift the playback timeline, so
§2.9's estimate-shift verdict reads zero by construction against it and
cannot be reused. §2.12 resolves this by giving the duck its own verdict
channel (capture-energy matched filter, worker-side) feeding a **new** policy
entry point `on_duck_result(dip_db, z, achieved_db, now_ns)`, while leaving
`SC_EVT_ACTIVE_PROBE`, `sc_evt_active_probe_t`, and `sc_notify_probe_executed`
— the shipped §2.9 surface — explicitly untouched. The spec is careful to
keep `policy.cpp`'s stated charter ("pure decision logic, no clocks, no DSP")
intact: the matched-filter/z-score computation is specced worker-side only,
and the policy entry point receives a result, never raw capture samples.

## Cross-section note

All three sections were checked against the live headers before writing
(`core/src/dsp/lag_window.h`, `core/src/dsp/lag_window.cpp`,
`core/src/policy/policy.h`, `core/tests/abi_c_check.c`,
`core/tools/lag_analyzer.cpp`'s CSV/flag conventions) rather than only against
the research doc's paraphrase of them, so section text cites real symbols:
`WindowLag`, `analyze_window`, `CorrectionPolicy`, `PolicyConfig`,
`on_referee_window`, `on_tick`, `probe_request_due`, `on_probe_executed`,
`sc_notify_probe_executed`. No existing test is authorized to change by any
of the three sections — unlike §2.8's "Deliberate test change" convention
(two named test updates), §2.10–§2.12 are additive-only at the spec level, so
no such callout was needed or written.

## What's next

`/to-tickets` decomposes each section into implementation tickets (working
titles: a beat-tracker ticket pair mirroring CTL-03a/03b's DSP/consumer
split for §2.10; a `--beta` corpus-A/B ticket plus a follow-on default-flip
ticket gated on its results for §2.11; an ABI/worker/policy/Kotlin ticket set
for §2.12, sequenced after the CTL-01 device pass per §3.5's note). Then
`/implement` with orchestrator-verified Sonnet subagents, per the standing
JTP workflow convention.

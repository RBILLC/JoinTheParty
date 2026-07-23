# Recognition API Market Audit — Offset-Capable Song Recognition · 2026-07-22

**Scope:** every viable engine for NAT-06's job — from a short live-mic sample, return *(track identity, playback offset within the track)* — audited against **primary sources only** (official pricing pages, official API docs, official SDK pages), fetched live on 2026-07-22. Every claim cites the URL that owns it. Where a page was unreachable or login-gated, that is stated rather than guessed.

**The non-negotiable:** the API must return the position *within the recognized track* where the sample sits (`RecognitionProvider.RecognitionFixResult.matchOffsetMs`). Metadata-only recognition is useless to this product (architecture-spec.md §3, §6.1's timing model `external_position(t) = matchOffset + (t − t_match) × (1 + skew)`).

---

## Summary verdict

1. **Only three engines are actually usable for this product today:** ACRCloud (`play_offset_ms`, millisecond), ShazamKit for Android (`matchOffsetInMs` + `predictedCurrentMatchOffset` + `frequencySkew`), and AudD (`timecode`, **1-second resolution only** — marginal for a sync engine).
2. **ShazamKit for Android is NOT dead.** The 2025 deprecation rumors are unsubstantiated: Apple's live pages serve ShazamKit Android **2.1.1** with recent updates (16 KB page-size support) and no deprecation notice anywhere. If the PM's ShazamKit→ACRCloud pivot rested on that rumor, the premise is false. Cost is a flat **$99/yr** (Apple Developer Program) with no per-request fee — unbeatable at scale, but with *undocumented* server-side quotas.
3. **The PM's "ACRCloud free tier is a 14-day trial" claim is essentially correct** — with two nuances: ACRCloud's public pricing table has been *removed* (pricing now lives behind console login), and the Terms mention a post-trial "Free tier" whose quota is published nowhere.
4. **Surprise contradicting our own docs:** ACRCloud's response schema **does document `frequency_skew`** (and `time_skew`) — docs/real-world-handoff.md §2 and `ACRCloudProvider.kt` both assert "ACRCloud reports no frequency skew" and hardcode `0.0`. The parser should be updated.
5. AcoustID/Chromaprint is **definitively out** (full-file identification by design; no offset field in the response; mic audio explicitly not expected to match). Self-hosted engines all return offsets but require building (and licensing) your own commercial-music catalog — a non-starter. Gracenote and Audible Magic are sales-gated with no public docs; Pex documents an offset (`asset_start`) but is not self-serve.

**Recommendation (detail in §"Recommendation" below):** primary **ShazamKit for Android** for the sustained low-cost path; fallback **ACRCloud** (already fully coded, keys away from live — use its 14-day trial to unblock INT-02 immediately). The `RecognitionProvider` seam means each is one provider class; both can ship.

---

## Comparison matrix

| Provider | Free tier / hobbyist pricing | Offset capability (exact field, resolution) | Scale cost (15k rec/mo → 1.5M rec/mo)¹ | Android constraints |
|---|---|---|---|---|
| **ACRCloud** | 14-day free trial, no card ([music-recognition](https://www.acrcloud.com/music-recognition/)); post-trial "Free tier" exists per [Terms](https://www.acrcloud.com/terms/) but quota unpublished. **Paid tiers no longer public** — console-login-gated | **`play_offset_ms`** — "The time position of the audio/song being played (millisecond)" ([metadata docs](https://docs.acrcloud.com/reference/identification-api/metadata/music)); plus `sample_*`/`db_*_time_offset_ms`, `score` 70–100, `external_ids.isrc`, **`frequency_skew`/`time_skew`** | **Unknown** — no public numbers; must read console pricing after signup (third-party ~$99/mo figures are unverifiable/stale) | REST + HMAC-SHA1 (already implemented in repo) or official Android SDK; keys-in-app is the SDK's own pattern but backend proxy safer |
| **ShazamKit (Android)** | No usage fee at all; requires **Apple Developer Program, $99/yr** ([purchase-activation](https://developer.apple.com/support/purchase-activation/)) for the Media-Services JWT token | **`matchOffsetInMs: Float?`** (ms) + **`predictedCurrentMatchOffset: Float?`** (auto-updating) + **`frequencySkew: Float?`** ([MatchedMediaItem](https://developer.apple.com/shazamkit/android/shazamkit/com.shazam.shazamkit/-matched-media-item/index.html)) | **$99/yr flat at any volume** — but quotas exist, are undocumented, and Apple says "file a Feedback" if hit ([forum, Apple staff](https://developer.apple.com/forums/thread/694291)) | AAR download gated behind Apple ID login (not on Maven); on-device SDK, minSdk 21; needs backend to mint the JWT developer token (seam already exists: `BackendClient.fetchShazamToken()`) |
| **AudD** | 300 free requests on signup, no card, no time limit stated ([audd.io](https://audd.io/)) | **`timecode`** — "the time in the recognized song when the fragment you sent is played" — **"MM:SS" string, 1 s resolution** ([docs.audd.io](https://docs.audd.io/)). No skew. ISRC only nested in `apple_music`/`spotify` blocks via `return=` | $75/mo (15k × $5/1k) → **above largest published tier** (500k/mo = $1,800, $3.60/1k); custom "from $2/1k" ⇒ ~$3,000–5,400/mo | Plain REST (`api_token`); docs say **don't put the token client-side** ⇒ backend proxy required |
| **AcoustID / Chromaprint** | Free non-commercial; commercial via AcoustID OÜ, no public prices (acoustid.biz **unreachable today**, HTTP 521) | **NONE** — response is `id`/`score`/`recordings` metadata only ([webservice docs](https://acoustid.org/webservice)); FAQ: "designed for identifying full audio files," not phone-mic snippets ([FAQ](https://acoustid.org/faq)) | n/a — fails the requirement | n/a — **ruled out** |
| **Self-hosted** (SoundFingerprinting; audfprint, dejavu, Panako, Olaf) | Software free (MIT / AGPL); **you must build & license your own reference catalog** — the real cost | All return offsets: `TrackMatchStartsAt` ([ResultEntry.cs](https://raw.githubusercontent.com/AddictedCS/soundfingerprinting/develop/src/SoundFingerprinting/Query/ResultEntry.cs)), audfprint "at X.X s", dejavu `offset_seconds`, Panako/Olaf match start/stop | Server hosting only — but catalog licensing for commercial music is prohibitive | Backend service (C#/.NET for SoundFingerprinting); audfprint/dejavu abandoned (2019/2020); Panako/Olaf AGPL |
| **Gracenote / Audible Magic / Pex** | None self-serve; all contact-sales. Pex: "$1 per file per month" marketing line only ([pex.com](https://pex.com/)) | Pex documents **`asset_start`/`asset_end`** "within the matched asset" ([Search Response](https://docs.pex.com/search/api-documentation/search-response/)) + `audio_pitch`/`audio_speed`; Gracenote/Audible Magic: no public docs | Unknown — sales conversations | Not viable for an MVP; **ruled out** |

¹ 15k/mo ≈ MVP (10 users × 50 recognitions/day); 1.5M/mo ≈ 1k users at the same cadence.

---

## Per-provider detail

### 1. ACRCloud (current baseline — `ACRCloudProvider.kt` is coded and awaiting keys)

**Free tier / trial — PM claim verified, with nuance.**
- [https://www.acrcloud.com/pricing/](https://www.acrcloud.com/pricing/) **no longer shows a pricing table** — it serves the homepage ("flexible pricing models ensuring you only pay for the recognition you use"; "Start Free Trial"; "No credit card required to start").
- [https://www.acrcloud.com/music-recognition/](https://www.acrcloud.com/music-recognition/): "**Get 14 days of free trial. No credit card required. Full API access.**"
- [Docs tutorial](https://docs.acrcloud.com/get-started/tutorials/recognize-music): "You will have 14 days for the free trial after registration."
- [Terms of Use](https://www.acrcloud.com/terms/): trial is 14 days *or* an unstated "Trial limit," whichever first; on termination "Customer may upgrade to paid service … or **downgrade to Free tier**." **No public page documents the Free tier's quota.** So: PM correct on the headline; a post-trial free tier nominally exists but is unquantified.

**Paid tiers — not publicly published.** `https://console.acrcloud.com/pricing` is login-gated (returns only the SPA shell). No plan names, prices, or overage rates appear anywhere on the public site today. Circulating ~$99/mo figures are third-party and unverifiable against the live site — treat as stale. **Action: sign up (free, email-verified, one account per person/company per Terms) and read console pricing before committing.**

**Offset — confirmed, millisecond.** [Music metadata reference](https://docs.acrcloud.com/reference/identification-api/metadata/music), verbatim:
- **`play_offset_ms`**: "The time position of the audio/song being played (millisecond)" — exactly `matchOffsetMs`. Referenced to the end of the sample, which is why `ACRCloudProvider` pairs it with `PcmWindow.endMonoNs` (the pairing SyncCore wants).
- `sample_begin_time_offset_ms` / `sample_end_time_offset_ms`: position of the recognition within the sample we sent; `db_begin_time_offset_ms` / `db_end_time_offset_ms`: within the database file.
- `score`: "Match confidence score. Range: 70 - 100" (the provider's `/100` mapping is right; note the floor is 70, so confidence never maps below 0.7 on a match).
- `external_ids.isrc` / `.upc` confirmed in the example JSON.

**Skew — documented, contradicting our handoff doc.** The same metadata page documents **`time_skew`** ("temporal misalignment or timing offset between two audio signals") and **`frequency_skew`** ("distortion in the frequency domain, where the spectral components … are shifted upward or downward"). docs/real-world-handoff.md §2 and the provider's KDoc say "ACRCloud reports no frequency skew" — the schema says otherwise. Unknown whether the field is populated on every music-identify response, but the parser should read it when present instead of hardcoding `0.0`.

**API shape — matches the repo implementation.** [Identification API reference](https://docs.acrcloud.com/reference/identification-api): `POST https://identify-<region>.acrcloud.com/v1/identify`, multipart, HMAC-SHA1 signature over `method\nuri\naccess_key\ndata_type\nsignature_version\ntimestamp` — exactly what `ACRCloudProvider.stringToSign`/`sign` implement. Sample guidance: "Files that are less than 15 seconds are generally better"; SDKs use 10 s per request ([tutorial](https://docs.acrcloud.com/get-started/tutorials/recognize-music)). Official [Android SDK](https://github.com/acrcloud/ACRCloudUniversalSDK) exists (keys configured in-app is its own pattern), but our REST provider is already done; the Terms contain no prohibition on embedded keys — only password confidentiality — yet the backend-proxy note in the provider KDoc remains the right production posture.

### 2. Apple ShazamKit for Android

**Alive, current, not deprecated — verified on live Apple pages.**
- [https://developer.apple.com/shazamkit/android/](https://developer.apple.com/shazamkit/android/) is live, serving docs for **ShazamKit Android 2.1.1**; changelog shows 2.1.0 "Adds support for 16 KB page sizes" (the Android-15-era Play requirement) and 2.1.1 "Improves audio recognition." [https://developer.apple.com/shazamkit/](https://developer.apple.com/shazamkit/) states "A ShazamKit SDK is also available for Android." No deprecation notice exists anywhere on developer.apple.com that the research could find (news feed searched; only deprecated symbol in the docs JSON is iOS's `SHMediaLibrary`). **The 2025 "discontinued" reports are unsubstantiated rumor as of today.**
- **Distribution:** an AAR (`shazamkit-android-release.aar`) placed in `libs/` with `flatDir` — not Maven — downloaded from [developer.apple.com/download](https://developer.apple.com/download/all/?q=Android%20ShazamKit), which **302-redirects to Apple ID sign-in** (login-gated). Documented deps: kotlinx-coroutines, OkHttp 4.12, Retrofit 2.11; minSdk 21.

**Account & token.**
- Android page, verbatim: "in order to use the ShazamCatalog you need to have an Apple Developer token, which you need to provide using your own DeveloperTokenProvider."
- The token is a JWT signed with a **Media Services private key**, created in Certificates, Identifiers & Profiles — "Required role: Account Holder or Admin," i.e. an Apple Developer Program team ([create-a-media-identifier-and-private-key](https://developer.apple.com/help/account/configure-app-capabilities/create-a-media-identifier-and-private-key)).
- **Cost:** "The Apple Developer Program annual fee is 99 USD" ([purchase-activation](https://developer.apple.com/support/purchase-activation/)). No other ShazamKit pricing exists.

**Quota:** none documented anywhere; Apple staff on the forums: "we're constantly revising the limits. In case you'll hit any threshold please file a Feedback" ([thread 694291](https://developer.apple.com/forums/thread/694291)). Real but unpublished server-side limits are the main scalability risk.

**Offset fields — confirmed on [MatchedMediaItem](https://developer.apple.com/shazamkit/android/shazamkit/com.shazam.shazamkit/-matched-media-item/index.html), verbatim:**
- **`matchOffsetInMs: Float?`** — "The difference between the start of the reference audio and the start of the sample audio." **Note: the Android name is `matchOffsetInMs` (milliseconds, Float), not iOS's `matchOffset`** as architecture-spec.md §3 lists it.
- **`predictedCurrentMatchOffset: Float?`** — "The auto updating playback position in the reference signature." (Units not stated by Apple; ms is the reasonable inference from its `InMs` sibling — flag for empirical verification.) **The old repo stub's name `predictedCurrentMatchOffsetInSeconds` does not match Apple's actual API.**
- **`frequencySkew: Float?`** — "A value of 0.0 indicates the matched audio at the original frequency, a value of 0.1 indicates 100hz is now 110hz." Exactly arch-spec §6.3's input.
- Also inherited: `timeRanges` / `frequencySkewRanges` (offsets within the reference signature).

### 3. AudD.io

- **Offset:** **`timecode`** — docs verbatim: "`timecode` is the time in the recognized song when the fragment you sent is played"; example `"timecode": "02:32"` — an **"MM:SS" string, 1-second resolution** ([docs.audd.io](https://docs.audd.io/)). The [enterprise endpoint](https://docs.audd.io/enterprise/) adds ms-level `start_offset`/`end_offset`, but those are positions *within the sent fragment*, not within the recognized track — the within-track figure stays 1 s. **For a sync engine whose whole job is sub-100 ms convergence, ±500 ms quantization on every fix is a serious observation-noise penalty** (the Kalman filter would carry it forever; the chirp calibrator can't remove it).
- **Pricing** ([audd.io](https://audd.io/), verbatim): "First 300 requests for free … no credit card"; "from $2 to $5 per 1000 requests"; tiers: 0+ = $5/1k; 100k/mo = $450; 200k/mo = $800; 500k/mo = $1,800; custom above that ("subscriptions begin at $2 per 1,000" per the enterprise page). MVP 15k/mo ≈ **$75/mo**; 1.5M/mo requires custom terms, ~$3,000–5,400/mo by the published unit prices.
- **API:** `https://api.audd.io/` GET/POST with `api_token`, file/URL/base64 audio; `return=apple_music,spotify` needed to get **ISRC (nested only** — `apple_music.isrc`, `spotify.external_ids.isrc`; enterprise docs add "ISRC and UPC codes require an enterprise account" for the enterprise endpoint). **No frequency-skew reporting anywhere in the docs.** Docs say "please don't include your API tokens in the client-side software" ([streams docs](https://docs.audd.io/streams/)) — backend proxy mandatory. Terms page body was not retrievable (JS-rendered) — ToS clauses unverified.

### 4. AcoustID / Chromaprint — ruled out definitively

- [FAQ](https://acoustid.org/faq), direct: "**The service has been designed for identifying full audio files**" — not short snippets, not phone recordings; even mooted future short-clip support would target clean stream audio, "not audio with background noise recorded on a phone." Mic samples are not expected to match at all.
- [Webservice docs](https://acoustid.org/webservice): lookup response is `status` + `results` (`id`, `score`, optional `recordings` metadata: title/artists/duration/releasegroups…). **No within-track offset/position field exists in the documented response.** Structurally whole-file vs whole-track matching.
- Pricing: free non-commercial ([acoustid.org](https://acoustid.org/)); commercial via AcoustID OÜ — acoustid.biz **unreachable today (HTTP 521, two attempts)**. Moot: fails the offset requirement on design.

### 5. Self-hosted open source — offsets yes, catalog no

All examined engines return the within-track offset; the blocker is that you must fingerprint your own copies of every reference track — for commercial music, a catalog-licensing problem no MVP should take on.

- **SoundFingerprinting / Emy** ([repo](https://github.com/AddictedCS/soundfingerprinting)): the standout — actively maintained (release 15.7.0, **2026-07-10**), C#/.NET backend-ready, MIT core. Offset is first-class: `ResultEntry.TrackMatchStartsAt` — "the time position in seconds where the origin track started to match the query" — plus `QueryMatchStartsAt`, `Coverage`, `Confidence` ([ResultEntry.cs](https://raw.githubusercontent.com/AddictedCS/soundfingerprinting/develop/src/SoundFingerprinting/Query/ResultEntry.cs)). Emy storage: community edition free non-commercial ([emysound.com](https://emysound.com/)); **emysound.com/pricing/ 404'd today** — commercial pricing unverifiable.
- **audfprint** ([repo](https://github.com/dpwe/audfprint)): mic-robust by design, offset in output ("… at 50.085 s"); **abandoned** (last commit 2019-09-23).
- **dejavu** ([repo](https://github.com/worldveil/dejavu)): `offset_seconds` in results, mic recognizer included; **abandoned** (last commit 2020-06-03).
- **Panako / Olaf** ([Panako](https://github.com/JorenSix/Panako), [Olaf](https://github.com/JorenSix/Olaf)): both report match start/stop within the reference, Panako adds speed/pitch robustness; **AGPL-3.0** (viral — problematic for a closed backend); Olaf latest release 2026-06-20, Panako Oct 2022, both "activity bursts" maintenance.

Worth keeping on the radar only for a hypothetical future where the catalog is user-provided audio rather than commercial music.

### 6. Enterprise: Gracenote, Audible Magic, Pex — ruled out for an MVP

- **Gracenote (Nielsen):** developer.gracenote.com **unreachable today (connection refused)**; [gracenote.com](https://www.gracenote.com/) has no developer portal, docs, or pricing — contact-sales only.
- **Audible Magic:** [audiblemagic.com](https://www.audiblemagic.com/) — no public pricing or API docs; only a login-gated AMOpen portal.
- **Pex (Vobile):** the one enterprise option with public docs showing an offset — `match_details.audio.segments[].asset_start`/`asset_end` "within the matched asset," plus `audio_pitch`/`audio_speed` ([Search Response](https://docs.pex.com/search/api-documentation/search-response/)) — but no self-serve credentials ([Authentication](https://docs.pex.com/search/api-documentation/authentication/) documents OAuth2 but no way to obtain keys), no public pricing beyond a "$1 per file per month" marketing line, and a UGC/rights-matching catalog focus. Sales conversation required; not MVP material.

---

## Recommendation

### Primary: ShazamKit for Android (restore it) — the scalable low-cost pick

Reasoning:
1. **Cost at scale is unmatched:** $99/yr total, zero per-recognition fee. Every metered competitor costs more by month two at MVP scale (AudD $75/mo) and by orders of magnitude at 1k users (AudD ~$3–5k/mo; ACRCloud unknown but certainly nonzero).
2. **It is the only engine purpose-built for this product's timing model:** `predictedCurrentMatchOffset` (arch-spec §3's "primary timestamp source" — extrapolated to *now*, removing the capture/processing-latency pairing burden) and a real `frequencySkew` for §6.3. No competitor offers the predicted-offset extrapolation at all.
3. **The reason it was abandoned is now disproven:** Apple's live pages show an actively maintained 2.1.1 SDK with no deprecation anywhere. If the PM pivot (docs/real-world-handoff.md) rested on the 2025 deprecation rumor, the audit removes that premise.
4. The repo already carries the ShazamKit-shaped seams: `BackendClient.fetchShazamToken()` (`POST /v1/tokens/shazam`) is built and tested; the token-minting backend is a small JWT signer over the Media Services key.

Accepted risks, stated honestly: quotas exist but are undocumented (Apple: "file a Feedback" if hit) — this is the one real scalability unknown; the AAR is login-gated (vendor it with provenance, same pattern as the Spotify AARs); `predictedCurrentMatchOffset` units are undocumented (verify ms empirically on first device run); membership is $99/yr with Account Holder/Admin needed to create the Media key.

### Fallback: ACRCloud (keep the coded provider — it's also the fastest path to first light)

`ACRCloudProvider.kt` is finished and one `Config(...)` away from live; `play_offset_ms` is genuine millisecond resolution; the 14-day trial costs nothing to start today. **Pragmatic sequencing: start the ACRCloud trial now to get INT-02's first real lock while the ShazamKit AAR + token backend are restored** — then ShazamKit becomes primary and ACRCloud remains exactly what architecture-spec §3 always called it: the fallback path. Before the trial ends, read the console pricing page (public pricing is gone) and decide whether to keep a paid ACRCloud floor or let it lapse to the unquantified free tier.

AudD is the emergency third string only: trivially simple REST and cheap at MVP scale, but 1-second offset resolution degrades the whole sync loop and there's no skew — use only if both primaries are unavailable.

### What changes in the codebase

The `RecognitionProvider` seam (android/app/src/main/java/com/jointheparty/app/recognition/RecognitionProvider.kt) means recognition-engine choice is one provider class + one Factory argument; SyncEngine, SessionViewModel, and the resolver don't change. Specifically:

1. **Re-create `ShazamKitProvider`** against the *real* AAR — the deleted stub's field names were wrong vs Apple's live docs: use **`matchOffsetInMs`** (not `matchOffsetInSeconds`) and **`predictedCurrentMatchOffset`** (not `predictedCurrentMatchOffsetInSeconds`); all `Float?`. Vendor `shazamkit-android-release.aar` into `android/app/libs/` with a provenance script (mirror `tools/fetch_spotify_sdks.sh`).
2. **Fix `ACRCloudProvider.parseMatch`** to read `metadata.music[0].frequency_skew` when present instead of hardcoding `frequencySkew = 0.0`, and correct the KDoc + docs/real-world-handoff.md §2 claim that ACRCloud reports no skew. (Also note: `score` floor is 70, so mapped confidence never goes below 0.7 on a match — fine, but document it.)
3. **Backend:** implement the real `POST /v1/tokens/shazam` JWT minting (the client seam exists); keep the ACRCloud path proxied server-side for production keys as already documented.
4. **No changes** to `RecognitionFixResult`, SyncEngine, or the ISRC→Spotify resolver — both primaries supply ISRC (ShazamKit natively; ACRCloud via `external_ids.isrc`).

---

## Corrections to prior repo claims (audit deltas)

| Repo claim | Audit finding | Source |
|---|---|---|
| PM: "ACRCloud free tier is a 14-day trial" | **Essentially correct**; nuances: public pricing table removed entirely (console-gated), and Terms name a post-trial "Free tier" with no published quota | [music-recognition](https://www.acrcloud.com/music-recognition/), [terms](https://www.acrcloud.com/terms/) |
| handoff §2 / ACRCloudProvider KDoc: "ACRCloud reports no frequency skew" | **Contradicted** — `frequency_skew` and `time_skew` are documented response fields | [metadata docs](https://docs.acrcloud.com/reference/identification-api/metadata/music) |
| arch-spec §3: ShazamKit Android field "`matchOffset`" | Android name is **`matchOffsetInMs`** (Float?, ms); old stub's `…InSeconds` names never matched Apple's API | [MatchedMediaItem](https://developer.apple.com/shazamkit/android/shazamkit/com.shazam.shazamkit/-matched-media-item/index.html) |
| Implied premise of the ShazamKit→ACRCloud pivot (2025 deprecation reports) | **No Apple deprecation exists**; SDK is at 2.1.1 with 2025-era updates (16 KB pages) | [shazamkit/android](https://developer.apple.com/shazamkit/android/), [shazamkit](https://developer.apple.com/shazamkit/) |
| arch-spec §3: "requires an Apple Developer Program membership for the token" | **Confirmed** — Media Services key requires Program team (Account Holder/Admin); $99 USD/yr | [media key help](https://developer.apple.com/help/account/configure-app-capabilities/create-a-media-identifier-and-private-key), [fee page](https://developer.apple.com/support/purchase-activation/) |

**Unreachable/ungettable today (stated, not guessed):** ACRCloud console pricing (login-gated SPA); AudD terms-page body (JS-rendered); acoustid.biz (HTTP 521); developer.gracenote.com (connection refused); emysound.com/pricing (404); Apple's AAR download listing (302 → Apple ID sign-in).

*Method note: findings gathered 2026-07-22 against the live primary pages cited above; four parallel research passes (AudD; ShazamKit; ACRCloud; AcoustID + open-source + enterprise), each instructed to quote exact field names and flag unreachable pages.*

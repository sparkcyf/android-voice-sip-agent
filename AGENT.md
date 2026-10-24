# AGENT.md

This file provides guidance to coding agents (Claude Code etc.) when working with code in this repository.
Machine-specific notes (devices, PBX hosts, current configuration) belong in the git-ignored `CLAUDE.local.md`.

Code, comments and commit messages are in English.

## Project overview

Turns an **unrooted** Android phone into a single-line **cellular ↔ SIP gateway** that registers to a PBX
(Asterisk / FreePBX / IssabelPBX) as a trunk. Privileged operations run through **Shizuku (shell uid 2000)**.
The SIP side has two interchangeable implementations: a hand-written pure-Kotlin stack (`:sip`, UDP only) and
pjsip 2.17's pjsua2 (`:sip-pjsua2`, UDP/TCP/TLS + SDES-SRTP, the default).

Verified on a Pixel 11 Pro (Android 17) and a Motorola phone (Android 16): cellular inbound → PBX, PBX outbound
via the phone, two-way audio, DTMF, Wi-Fi reconnect (also mid-call), handset muting while bridged, the takeover
switch, overnight Doze standby, 15-minute calls without clock drift, and pjsua2 calls both on a LAN (UDP) and
through a public Flexisip proxy (TLS + SRTP). Not yet verified: call waiting, DTMF and network-loss recovery under
pjsua2, public calls over cellular data only (Wi-Fi off).
Community reports (README "Tested on") cover five more Android 13–16 phones, which give two-way audio only with
`audio_method=2` (direct) and earpiece mute off; Shizuku 13.6.0 cannot start the UserService on MediaTek/Transsion (use 13.5.4).

## Common commands

```bash
sip-pjsua2/native/build-pjsua2.sh                # once / on upgrade: builds OpenSSL 3.5.9 + libopus 1.6.1 + pjproject 2.17 (arm64) into git-ignored src/generated/
./gradlew :sip:test :gateway:test                # JVM unit tests (no native build needed)
./gradlew :sip-pjsua2:testDebugUnitTest          # INVITE parsing etc.
./gradlew :sip:test --tests 'io.github.sparkcyf.voicesipagent.sip.SipMessageTest'          # single test class
./gradlew :gateway:test --tests 'io.github.sparkcyf.voicesipagent.gateway.CallBridgeTest.cellularInboundToSipFullCall'
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease                   # signed only if vsa.signing.* is set in ~/.gradle/gradle.properties
export ANDROID_SERIAL=<serial>                   # zsh does not word-split A="adb -s …"; use the env var
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s VoiceSipAgent VsaPrivileged VsaTap
```

adb control (the receiver is guarded by the DUMP permission, so only the shell can send to it; **always write the
fully-qualified component name** — `.AdbControlReceiver` resolves to a non-existent class and the broadcast is
silently dropped). Replies wait up to 5 s for a `gateway-runtime` queue checkpoint:
`result=1` acknowledges submission with observed state, `result=2` invalid input or submission/reporting
failure, `result=3` queued or running without acknowledgement. Result 1 does not guarantee completion;
worker exceptions, deferred changes and SIP registration are not tracked as command outcomes. `data`
contains status or saved/queued selections (never the password). Check logcat or the in-app log before
retrying errors/timeouts: submitted work is not cancelled. Unknown arguments and wrong types are rejected,
including `--ez hold_sip`. `result=0` with no data is not an acknowledgement; check the component name:

```bash
R=io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver
adb shell am broadcast -n $R --es op status            # one-line reply; full report goes to logcat (VoiceSipAgent)
adb shell am broadcast -n $R --es op takeover --ez on false
adb shell am broadcast -n $R --es op earpiece-mute --ez on true
adb shell am broadcast -n $R --es op loopback --ez on true   # inbound echo test; in-memory only, reset on reinstall
adb shell am broadcast -n $R --es op provision --es user … --es password '…' --es target …   # only overwrites the given fields
adb shell am broadcast -n $R --es op setup | sip-restart
adb shell am broadcast -n $R --es op sip-stack --es stack pjsua2|builtin
adb shell am broadcast -n $R --es op provision --es transport tls --es srtp optional …   # transport/srtp: pjsua2 only
adb shell am broadcast -n $R --es op provision --ei audio_method 2   # 0 auto, 1 redirection, 2 direct; --ei call_access 0|1|2
adb shell am broadcast -n $R --es op sip-debug --ez on true   # pjsip info-level logging, off by default (persisted)
adb shell "am broadcast -n $R --es op provision --es prefixes ''"   # empty string args need an extra layer of quotes, or am says "Argument expected"
```

`:sip:sipProbe` (`-PprobeArgs="echo *43 40"` / `"listen 60"`) registers against the PBX described in the
git-ignored `sip_secret` and runs a live call test. Do not point it at the gateway's own trunk account: with
`remove_existing=yes` it would kick the phone's registration.

Toolchain: Gradle 9.5.1 wrapper, AGP 9.3 (built-in Kotlin), Kotlin 2.2.10, compileSdk 37, minSdk 30,
targetSdk 36; JVM modules target Java 17 bytecode using the Gradle JDK. The APK packages arm64-v8a and
x86_64; build both with `ABIS="arm64-v8a x86_64" APP_PLATFORM=android-30` before assembling. To package another ABI, update
the app's Gradle `abiFilters` and build matching native libraries.
Android 11–13 compatibility and physical audio are provisional until verified on devices.

For native size analysis, run `NATIVE_SIZE_REPORTS=1 sip-pjsua2/native/build-pjsua2.sh` (off by default).
Each ABI work directory gets `pjsua2.link.map`, `pjsua2.sections.txt`, and `pjsua2.symbol-sizes.txt`.
Reports inspect the ELF before APK stripping; section totals differ from packaged size. Save reports
before rebuilding, which recreates ABI work directories.

## Architecture

Module dependencies: `:voice-api` ← `:sip`, `:gateway` (pure JVM, testable on the host); `:telephony`,
`:privileged`, `:sip-pjsua2` (Android); `:app` wires everything together.

- **Contract (`:voice-api`)**: `CallEndpoint` (`dial()` never throws; failures come back as an ENDED leg),
  `CallLeg` (state only moves forward: NEW→PROCEEDING→RINGING→CONNECTED→ENDED), `MediaPort` (mono PCM16,
  20 ms, fixed `PcmFormat` per port: 8 kHz = 160 samples/320 bytes, 16 kHz = 320 samples/640 bytes;
  `readFrame()` never blocks). Both ports must match; the bridge rejects mismatches. A new voice/control integration = one more
  `CallEndpoint`; the core does not change.
- **`CallBridge` (`:gateway`)**: bridges any two endpoints. An inbound call on side A first dials side B, and
  **A is answered only after B answers**; timeouts/failures map to a `HangupCause` rejection. `EchoEndpoint` is
  for loopback tests.
- **Single clock domain**: `MediaBridge` is driven by the port with `isClockSource` (the cellular tap, i.e. the
  baseband clock); both directions follow that one clock. `RtpSession` has no clock and relies on its jitter
  buffer. A 20 ms ticker is used only when there is no clock source.
- **Cellular side (`:telephony`)**: call access is either a Telecom **companion InCallService**
  (CALL_COMPANION_APP + MANAGE_ONGOING_CALLS appop; setup also attempts `cmd telecom add-or-remove-call-companion-app`)
  or an explicitly selected default-dialer UI service. Audited stock API 30 requires default-dialer mode.
  API 31+ readiness uses the enabled non-UI service and MANAGE_ONGOING_CALLS; it does not require
  a companion override or dialer role. See docs/ANDROID12_SOURCE_AUDIT.md and docs/ANDROID13_SOURCE_AUDIT.md.
  `CellularEndpoint` only handles calls it owns: gateway inbound calls and calls it dialed itself (matched on the
  last 8 digits). Calls the user dials manually and calls arriving while another call exists are left alone;
  emergency numbers are refused fail-closed. Once a call is ACTIVE it is `setMuted(true)` and the audio tap is
  opened (up to 5 retries).
- **Privileged process (`:privileged`)**: a Shizuku UserService running in an app_process with shell uid 2000,
  AIDL interface `IPrivilegedService`. Call audio is handed to the app over a **SOCK_SEQPACKET socketpair**
  (one packet = one frame); the app side is `PcmSocketPort` (the clock source). The UserService `version` is the
  APK's `lastUpdateTime`, so every reinstall restarts the shell process and loads the new code.
  `openCallAudio(method, sampleRate)` uses 8 kHz for Kotlin SIP and 16 kHz for pjsua2, including loopback.
  There is no rate fallback; existing official/legacy method selection and same-rate setup retries remain.
  App-side handle close shuts down the local socket immediately on the Telecom main thread. Helper close,
  restoration reporting and final stats run on a serialized `audio-cleanup` worker whose thread expires
  after one idle second; none waits on main for an in-flight open/status transaction. Queued cleanup checks
  its generation before IPC so it cannot close a newer tap. Remote restoration can finish after hangup.
  Capture failures are recovered inside the tap, keeping the app's socket and the call (`TapRecovery`):
  a direct record read <= 0 with our voice-call stream mute applied withdraws that mute, refuses it for
  the rest of the call and re-creates the record once; any other direct read <= 0 gets one more re-create.
  AUTO also switches mid-call to the next backend when redirection capture fails or its downlink is digital
  zero (peak 0) for the first 3 s. The AUTO order is the single constant `TapRecovery.AUTO_ORDER`
  (redirection first; direct-first is pending tests of direct on the Pixel and moto). A mute failure within
  3 s of the mute is remembered per build fingerprint by the app, which then blocks direct stream mute
  (`setDirectOutputMuteBlocked`); changing the earpiece-mute setting clears it. The recovery paths themselves
  are not device-verified. Pixel 11 Pro (Android 17, 2026-10-17): redirection-manual gives two-way audio;
  direct gives two-way audio with earpiece mute off, but with the voice-call stream mute the direct downlink
  capture is attenuated (peak <2k vs ~20k) without any read error, so `TapRecovery` does not trigger.
  Diagnostics: the 5 s `levels` line adds per-window max frame gaps (`downGap5s`, `upGap5s`, `upWrite5s`) and
  tap stats add totals (`GapStats`); pjsua2 logs `call N rtp rx[...] tx[...] jbuf[...] fromSip/toSip[...]`
  every 5 s and a final line when the stream is still available. Counters only, never audio.
  The new 16 kHz tap has not yet been verified on a device.
- **SIP (`:sip`)**: UDP; all protocol state runs on a single `sip-ua` scheduler thread (`post {}`). Outbound calls
  carry the caller number in `P-Asserted-Identity`; for inbound calls the cellular number to dial comes from the
  `X-VSA-Dial` header, falling back to the Request-URI user part. PCMU/PCMA + telephone-event(101) only.
  Registration renewal preserves readiness while the previous binding is unexpired; a separate monotonic
  expiry timer, renewal failure or shutdown withdraws readiness. Superseded registration responses are ignored.
  `stop()` waits for best-effort unregister, then up to 1 s for queued socket closure. Its returned future
  reports local socket release, including cleanup that finishes after the bounded wait; repeated stops
  return the same completion. A PBX reply is not required to release the socket.
- **SIP (`:sip-pjsua2`)**: `PjsipEndpoint` implements `CallEndpoint` with the same trunk semantics as `:sip`.
  - pjsua is a process singleton: ordinary endpoint restarts reuse `PjsuaLib`; restarting the endpoint only
    adds/removes accounts, transports (keyed by type + port) are kept and reused. Every pjsua call and SIP callback
    runs on a single `pjsua` thread (`threadCnt=0` + `mainThreadOnly`, looping over a task queue and
    `libHandleEvents`). The SIP event wait is capped at 100 ms without call media and 10 ms with media;
    sockets and native timer deadlines wake it earlier, while queued app controls can wait up to 100 ms.
    The RTP/RTCP manager retains its own ioqueue and one worker (`medConfig.hasIoqueue=true`,
    `threadCnt=1`, the PJSIP defaults). That worker uses a 10 ms maximum socket wait even without
    calls; slower SIP polling does not remove its idle wakeups. CPU and battery costs are unmeasured.
    Retain this separation so SIP callbacks and queued app commands cannot hold up RTP reception
    and jitter-buffer filling. Sharing the SIP ioqueue with zero media workers is supported, but
    stalls on the shared thread can cause audio gaps; compilation and unit tests do not establish
    audio quality. A possible future optimization is a longer maximum wait in PJMEDIA's
    `pjmedia/src/pjmedia/endpoint.c` `worker_proc`: ready sockets wake the wait early. This requires
    a native rebuild and device validation of call startup, audio timing, and shutdown.
    Blocking native initialization and DNS run on a separate serialized `sip-startup` executor whose
    thread expires after one idle second. Takeover-off cancels pending account creation and queues
    library teardown there, after outstanding initialization/DNS and before replacement startup.
    Teardown drains deferred wrapper deletions and destroys the native library on the `pjsua` thread.
    Native cleanup can finish after gateway controls return; initialization/DNS is not interrupted.
    An unexpected event-loop exception reports SIP unavailable and ends local SIP legs with NETWORK_ERROR.
    Accepted controls and deferred wrapper deletions drain on the owner thread before native destruction;
    queued account creation is skipped after failure. Ordinary task exceptions only log. Takeover and dialer
    ownership remain unchanged; use Reconnect SIP or off/on to recover (no automatic library restart).
    Its timers, event thread, sockets, and null-device clock then stop; switch-on creates a new library
    lifetime. Physical off/on cycling remains a device validation check.
  - **No Android sound-device backend is compiled in** — only the null sound device, so pjsua never touches
    AudioManager or the audio mode. Audio enters/leaves the conference bridge through `AudioMediaPort`
    (`ConfPort`, 16 kHz / 20 ms, no EC). Opus uses 16 kHz mono PCM (20 kbit/s target);
    PJMEDIA resamples for PCMU/PCMA fallback. G.722 is disabled. Keep the resampler compiled in.
    VAD/DTX is enabled only for Opus; PCMU/PCMA have VAD disabled. Global `noVad=false` allows the
    per-codec settings to apply. Set the codec parameters before `codecOpusConfig`, which regenerates
    Opus fmtp (`usedtx=1`); PJSIP honors the peer's negotiated `usedtx`. PCM clocks stay at 20 ms.
    Quiet speech, tones/hold music and prolonged-silence PBX/NAT behavior remain unverified on devices.
    A `FrameFifo` of at most 5 frames on each side absorbs
    the drift between the two clocks.
  - SWIG objects (Call/Account/AudioMediaPort) must be `delete()`d explicitly on the pjsua thread, and never from
    inside their own callback (`finish()` defers the delete to the next loop). `Account`: `shutdown()` before
    `delete()`.
  - `stop()` attempts un-REGISTER even while registration is pending and waits for a successful
    zero-expiry response (≤1.5 s): the new account reuses the same Contact, and a late un-REGISTER
    could remove the new binding. If the native unregister request throws, cleanup skips the wait;
    unregistration remains best effort.
    Rejected cleanup submissions, wait timeouts and individual cleanup failures are logged. Failed call
    hangups or account operations do not skip subsequent cleanup. `transportClose` is graceful and can
    retain the socket; only successful `stopLibrary()` completion confirms native destruction.
  - Build patches (`native/`): `config_site.h`, `pjsua2-java-ext.i` (bulk-copy typemap for ByteVector; must come
    before `%template(ByteVector)`; SWIG director threads stay attached), `pjsua2-java-profile.i` (feature-group
    exclusions applied after upstream director declarations; retain Account/Call/AudioMediaPort/LogWriter
    directors, setNullDev and PCM copies), `pjsip-tls-wildcard.patch` (RFC 6125
    wildcard certificate matching). pjsua's log callback is gated by `consoleLevel`, not just stdout.
  - Native dependencies and the wrapper use NDK ThinLTO and section GC. `pjsua2.exports.map` localizes
    everything except JNI entry points; `build-pjsua2.sh` checks the final dynamic export table.
    Exported SWIG wrappers remain native reachability roots; R8 does not prune them.
    Tarballs and shared OpenSSL/Opus sources live at the work directory's top level; ABI directories
    hold out-of-tree dependency builds, `prefix/{openssl,opus}` and isolated PJSIP source/build trees.
    Each requested ABI directory is recreated and built from scratch, including dependency installations,
    PJSIP patches and JNI output. Shared sources are retained; there is no build fingerprinting/object reuse.
    Tarball SHA-256 checks remain. SWIG generates Java bindings and the C++ wrapper once per invocation;
    the wrapper is compiled and linked separately for each ABI. Generated Java/JNI output is published after
    all ABIs succeed.
    Do not run concurrent builds sharing the same work/output directories.
  - Release R8 is enabled. Module consumer rules preserve generated SWIG classes, director callback
    overrides and the Shizuku `PrivilegedService(Context)` reflection entry point. Do not remove these
    rules without auditing generated JNI lookups and testing a release APK in both app and shell processes.
- **Runtime (`:app`)**: `GatewayRuntime` singleton; every state-changing operation runs on the single-threaded
  `gateway-runtime` executor. It is started from `GatewayApp.onCreate` (Telecom may start the process just to bind
  the InCallService). Process startup initializes the permanent cellular observer; SIP, bridge, Shizuku,
  network monitoring and health polling start only after `GatewayService` successfully enters foreground.
  Takeover requests the foreground service even before dialer qualification or privileged setup succeeds.
  Service ownership is tracked from creation, so destruction also cancels role requests if promotion fails.
  Service destruction queues the same resource cleanup as switch-off, cancels pending role changes, and
  never acquires/restores a role or immediately restarts itself. A service token prevents an old destruction
  callback from stopping a replacement service. Telecom observation and role recovery UI remain available.
  `SipRegistrationGate` (setting `holdSipWhenNotReady`, default on) keeps SIP unregistered while the
  cellular side minus SIP itself is not ready: held from each gateway start until first ready, then only
  after a 45 s grace period; readiness re-registers at once. Hold changes go through `startSip()`/`stopSip()`
  (pjsua2 keeps library and transport) and are deferred while calls or a bridge session exist.
  Replacement SIP endpoints wait for pending local socket release, including native destruction when
  leaving pjsua2. Completion returns to `gateway-runtime` and checks endpoint identity and gateway state
  before starting, so rapid restarts/off-on cannot start an obsolete endpoint. Release failures leave
  the replacement unavailable and are logged; ordinary pjsua2 account restarts still reuse the library.
  While takeover is enabled and the service is foreground, a health check runs every 60 s and re-runs setup while idle if
  companion mode is selected, and call access is unavailable. The override alone does not gate readiness;
  health checks never request the dialer role.
  Settings live in SharedPreferences; the SIP password is sealed with a Keystore AES-GCM key.
  Takeover-off cancels health/dialer polling, unregisters network monitoring, and removes
  this app's Shizuku listeners and UserService after explicit handoff cleanup. Saved-off startup
  starts none of those resources. The standard runtime executor releases its thread after one idle
  second while off; SIP/bridge delayed work is cancelled on shutdown. The shared Shizuku server is
  not stopped. While the app holds the default-dialer role, the foreground service keeps a reminder
  visible even when takeover is off. Its passive dialer-change receiver updates or removes the
  notification when the role changes. Shutdown attempts dialer restoration using the existing helper
  connection, then releases it. Restoration that cannot complete exposes system default-app settings;
  role actions while off use Android's selection UI. Failed or cancelled requests are cleared, and
  no role retries run while off. Re-enable restores gateway background work.
  Explicit dialer handoff and takeover-switch actions use narrow Shizuku AIDL methods and `cmd role` for the owning app's
  Android user. `DialerHandoffStore` commits the previous dialer before acquisition, separately from
  SIP settings. Restore the saved dialer first, then try the system fallback; verify the actual holder.
  Runtime defers handoffs for bridge/owned calls and the helper checks Telecom for all ongoing calls,
  including calls invisible to our InCallService before acquisition. Pending requests retain the
  expected holder and can be cancelled; never replay them on startup or reclaim the role in setup.
  Keep system role selection/settings available when Shizuku is unavailable or a handoff fails.

### Takeover switch and handset muting

- **Takeover is the primary control**: an explicit switch-on acquires the role when effective call access is
  default dialer. Switch-off disables companion integration and unregisters SIP before restoring the saved
  dialer, unless `keepDefaultDialer` is enabled. Independent role controls and the retention option live in
  Advanced; failures expose recovery actions. External role changes never change the takeover setting.
  `TakeoverControl` captures handoff intent before waiting for calls, preserves an active bridge during rapid
  toggles, and applies gateway shutdown before restoration. Turning off during a call takes effect when current
  Telecom calls and the bridge session end (`takeoverOffPending`); enabling can also wait (`takeoverOnPending`).
  Keep dialer activity/service enabled while holding the role, including after failed restoration.
  Startup/setup/health checks never acquire or restore roles; only explicit actions initiate handoff.
  Switch-on captures the owning user's current dialer through Telecom before the helper reconnects.
  Pending acquisition runs only with takeover enabled and the helper connected; switching off, cancellation,
  or a different holder prevents replay. Helper disconnections defer rebinding until Shizuku clears its
  dead connection; each bind has a new generation to reject stale callbacks.
  API 31+ Automatic returns to companion access after restoring the role; explicit DIALER selection reacquires
  on each switch-on. Changing the selection or retention setting alone never mutates the role.
- **The companion override is a plain list**: adding appends a duplicate, removing deletes one entry. `setup` must
  count the existing entries before adding or removing them one by one.
- **Earpiece mute**: supported redirection taps switch `IN_CALL(2)` to `MODE_CALL_REDIRECT(5)`, tearing down
  the baseband ↔ earpiece/mic route while the redirected record/track keep working. Direct taps (also API 30–32)
  keep IN_CALL and use `adjustStreamVolume(STREAM_VOICE_CALL, ADJUST_MUTE, 0)` under the helper's identity with
  MODIFY_PHONE_STATE. Telecom manages microphone mute separately. Tap close restores only our changes:
  NORMAL withdraws our mode request; ADJUST_UNMUTE clears an owned stream mute without changing its volume
  index. Preserve an initially-muted stream and retain failed restoration for retry. AudioService drops mode
  requests on helper death, but abrupt death can leave global stream mute active; raising call volume can
  unmute it. Stream-mute effectiveness and capture/injection interactions require physical verification.
  `cmd audio adj-mute` no longer exists on Android 17.

## Hard-won lessons (read before touching audio/privileged code)

- **AudioFlinger attribution**: Shizuku can bootstrap the gateway package under uid 2000, which AudioFlinger
  rejects. API 30 uses a helper-local operation-package adapter. Detect `setContext(Context)` separately on
  record and track builders: the documented introductions are API 31 for AudioRecord.Builder and API 34 for
  AudioTrack.Builder. Audited API 30 has neither; the inspected API 32 device has only the record setter.
  Use an explicit attributed context where supported; otherwise verify helper-local process attribution before
  construction. The fallback wraps the existing Application base context, overriding only the operation package
  on API 30 and also the attribution source on API 31+, preserving the Application and its real package.
  Never merely skip a missing setter:
  construction can succeed with mismatched attribution and silently muted playback.
  Call helpers are attempted only when their default attribution matches. Otherwise use equivalent manual
  redirection construction. Root attribution must use the actual uid rather than shell uid 2000.
- **Redirection audio attributes must match the framework helpers exactly**: the record's capture preset is
  `VOICE_DOWNLINK(3)`; the track uses system usage `USAGE_CALL_ASSISTANT(17)` + `CONTENT_TYPE_SPEECH` plus
  `setCallRedirectionMode(CALL_REDIRECT_PSTN)`. These are hidden APIs, called via reflection. Without them the
  record silently falls back to the microphone and the track plays as media through the local speaker, **while
  Telecom state and frame counters look perfectly normal** — only the `VsaTap` `levels`/`downVoiced` logs and a
  human listener can tell.
- **The device is the source of truth for hidden APIs**: `adb pull /system/framework/{framework,services}.jar`,
  extract the dex, and disassemble the target method with `$ANDROID_SDK/build-tools/37.0.0/dexdump -d`
  (e.g. `AudioManager.getCallDownlinkExtractionAudioRecord`, `AudioService.setMode`).
- The companion override is lost on reboot or a Telecom restart; setup attempts to restore it where applicable.
  Losing it alone does not trigger periodic setup or block API 31+ non-UI access. After a reboot the phone is
  in BFU and non-root Shizuku must be restarted by hand; start-on-boot is a non-goal.
- **Shizuku binder delivery after a server restart**: the server pushes its binder to client apps from a
  startup scan of installed packages and from uid/process observers (uid active/idle/uncached, process gaining
  foreground activities). On the Pixel 11 Pro (Android 17, Shizuku 13.6.0) the startup scan reaches only the
  manager, and a running foreground-service app makes no uid transition, so `ShizukuConnector` never receives
  the binder. Bringing `MainActivity` to the foreground (same pid) triggers delivery immediately. The manager's
  provider requires INTERACT_ACROSS_USERS_FULL, so the app cannot pull the binder itself. Readiness and the
  notification therefore tell the user to open the app; there is deliberately no periodic self-restart.

## PBX integration

- The gateway is a **trunk**, not an extension (`context=from-trunk`, `trust_id_inbound=yes`,
  `remove_existing=yes`, ulaw/alaw only). See README for the reference `pjsip_custom_post.conf` /
  `extensions_custom.conf`.
- With a registrar-routing proxy in between (e.g. Flexisip), requests from the PBX are routed by Request-URI user
  part, so the PBX must keep the gateway's own AoR there and pass the number in `X-VSA-Dial`
  (`PJSIP/<number>@trunk` would 404).

## Secrets and privacy

- `sip_secret`, `*.secret`, `local.properties` and `CLAUDE.local.md` are git-ignored. **Never commit them and never
  echo passwords in output.** When provisioning a phone, read the password into a shell variable and pass it
  straight to the adb broadcast.
- Phone numbers are logged with only their last 2 digits (`redactNumber()`); PCM is never logged or written to
  disk — only levels/counters.

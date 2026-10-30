# Voice SIP Agent

[中文说明](README.zh.md)

Turn an **unrooted** Android 11+ phone into a single-line **cellular ↔ SIP gateway**. The phone registers to your PBX
(Asterisk / FreePBX / IssabelPBX) as a SIP trunk:

- **Cellular → SIP**: an incoming cellular call rings a DID on the PBX; the cellular call is answered only once
  someone picks up on the SIP side, and the caller ID is passed in `P-Asserted-Identity`.
- **SIP → cellular**: the PBX sends a call to the trunk and the phone places it on the cellular network.
- Call audio is captured and injected through direct telephony routing or Android's call-redirection APIs.
  Local call output can be muted while bridging; the handset microphone stays muted.

Privileged operations (call-audio interception, the Telecom companion override, appops) are performed through
[Shizuku](https://shizuku.rikka.app/) running as the shell user (uid 2000) — **no root required**. This relies on
hidden platform APIs, which is why the app won't be available on Google Play.

> [!WARNING]
> This is a hobby project built on undocumented Android behaviour. It may stop working with any OS update, may
> drop or misroute calls, and is provided **as is, without warranty of any kind**. Read the
> [Disclaimer](#disclaimer) before using it.

> [!IMPORTANT]
> The logic and code of this app is mostly written by LLM agents. The repo owners do not claim to understand some of the code here.

## Status

Tested on:

| Device | Android | Notes |
|---|---|---|
| Google Pixel 11 Pro | 17 | Main development device |
| Motorola | 16 | Works |
| POCO F6 (HyperOS 2) | 15 | Two-way audio with Direct telephony and local output mute off¹ |
| vivo V30 Lite | 15 | Same¹ |
| POCO F3 (MIUI 14) | 13 | Same¹ |
| Samsung Galaxy A15 5G (One UI) | 16 | Same¹ |
| Redmi Note 13 Pro (HyperOS 2, MT6789) | 15 | Same¹ |
| Infinix X678B | 14 | Registers; call audio not tested¹ |

¹ Community report ([#1](../../issues/1)). Other audio settings did not give two-way audio on these devices; see
[Troubleshooting](#troubleshooting).

Verified: inbound and outbound calls, two-way audio, DTMF (RFC 4733), Wi-Fi reconnect (also mid-call), overnight
Doze standby, long calls without clock drift, handset muting, LAN calls over UDP and public calls over TLS + SRTP
through a Flexisip proxy.

Not yet verified: call waiting, DTMF and network-loss recovery with the pjsua2 stack, public calls over cellular
data only.

The default pjsua2 SIP stack supports Opus with G.711 fallback. See [audio formats and routing](TECHNICAL.md#audio-formats-and-routing)
for codec settings and further validation gaps.

Limitations: one call at a time. Early media (ringback tone, busy signal, etc.) is unimplemented. The APK packages arm64-v8a and targets Android 11+ (minSdk 30).
Android 11 remains untested. Hidden APIs and audio HAL routing differ across vendors, so other devices
may need different audio settings or may not work at all.
Default-dialer mode provides gateway status and recovery, with a switch-dialer action for manual calling.
Emergency numbers are always refused and are never dialed through the gateway.

## Installation

1. Install and start [Shizuku](https://shizuku.rikka.app/guide/setup/) (wireless debugging or adb). Shizuku must
   be started again by hand after every reboot.
2. Download `voice-sip-agent-<version>.apk` from [Releases](../../releases) and install it.
   Verify the signing certificate if you like:

   ```bash
   apksigner verify --print-certs voice-sip-agent-0.1.0.apk
   ```

   The SHA-256 digest of the release signing certificate is
   `b8fe38aa02f9657c6785fb6faeb20abb20deba410f9c643fcd93269b642850c4`.
3. Open the app, enable takeover if off, then tap **Grant Shizuku** in **Health** and allow access.
   Setup runs automatically.
   On Android 11, enabling **Take over calls** also makes this app the default dialer.
   Newer versions normally work alongside your existing dialer as an InCallService companion app.
4. Fill in **Settings** (PBX host/port, transport, SRTP, user, password, …) and tap **Save & apply**.
5. Enable **Take over calls** to bridge calls to the PBX. Switching off waits for current calls to end.
   In default-dialer mode, Shizuku saves your previous dialer and restores it when takeover is switched off,
   unless role retention is enabled in Advanced settings. If restoration fails, use **Choose dialer in Settings**.

See [Advanced settings](#advanced-settings) for call-access modes, audio choices, and dialer recovery.

For local listening, turn **Mute call audio on this phone** off in **Takeover** before the call.
Downlink output follows the phone's current call audio route (earpiece, speaker, or headset) and volume.
The handset microphone stays muted while bridging, so local listening does not enable local speech.

## Configuration

| Setting | Meaning |
|---|---|
| SIP stack | `pjsua2` (default; UDP/TCP/TLS, SRTP) or `builtin` (pure Kotlin, UDP only; deprecated, new features target pjsua2) |
| PBX host / port | Registrar / proxy address |
| Transport | `udp` (LAN), `tcp` or `tls` (pjsua2 only) |
| SRTP | `off`, `optional` (offer SDES, fall back to RTP) or `mandatory` (488 otherwise); only effective with TLS |
| SIP domain | Defaults to the PBX host |
| User / password | Trunk credentials on the PBX |
| Cellular inbound → SIP target | DID dialed on the PBX for incoming cellular calls; blank = the SIP user name |
| Allowed dial-out prefixes | Comma-separated; empty = any number may be dialed out |
| Unregister while the phone cannot take calls | On (default): at startup or switch-on, waits for the cellular side (Shizuku, call access and call audio) to be ready before registering with the PBX. After first readiness, a problem must last 45 s before unregistering so the PBX can fail over. Registration changes wait for calls to end and registration resumes as soon as the phone is ready. While SIP remains registered but the phone is unavailable, incoming calls receive `480 Temporarily Unavailable`. Turning this option off allows SIP registration regardless of local readiness; unavailable calls still receive `480`. |

Tap **Save & apply**; changes wait for current calls to end. With takeover off, saved SIP settings are
used when takeover is next enabled.

TLS validates the server certificate chain and host name against the Android system CA store (TLS 1.2/1.3 only)
and accepts `*.` wildcard certificates in the left-most label (RFC 6125). There is no downgrade: if TLS fails,
registration fails and is retried every 30 s.

### Gateway status and recovery

Check **Health** for setup or registration errors. With takeover on, use **Re-run setup** after granting
Shizuku access, or **Reconnect SIP** to retry registration with saved credentials.

### Advanced settings

Advanced settings are collapsed by default. Leave **Cellular audio** on Automatic for normal use.
**Call access** offers these modes:

| Choice | Behavior |
|---|---|
| Automatic | Uses default-dialer mode on Android 11; on Android 12+, uses companion mode unless this app already holds the dialer role. |
| Alongside dialer (companion) | Keeps your usual phone app and call UI. Android 12+ only. |
| Default dialer | Makes the gateway the default phone app on the next takeover switch-on. Switch back to your usual dialer for manual calling. |

Selecting a mode does not change your default phone app. Default-dialer mode restores the previous dialer
on switch-off unless **Keep this app as the default phone app when gateway is off** is enabled.
Use **Make default phone app** for an explicit switch, or **Choose dialer in Settings** for recovery.

**Cellular audio** also offers Direct telephony and Call redirection (Android 13+). See
[call access and takeover](TECHNICAL.md#call-access-and-takeover) and
[audio formats and routing](TECHNICAL.md#audio-formats-and-routing) for role controls, test options, and diagnostics.

### adb control

The app can also be driven over adb (the receiver requires the `DUMP` permission, which only the shell user has):

```bash
R=io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver
adb shell am broadcast -n $R --es op provision --es server sip.example.org --ei port 5061 \
  --es transport tls --es srtp optional --es domain sip.example.org \
  --es user android-gw --es password "'<secret>'" --es target android-gw   # only overwrites the given fields
adb shell am broadcast -n $R --es op status            # one-line summary; full report in logcat
adb shell am broadcast -n $R --es op takeover --ez on false
adb shell am broadcast -n $R --es op earpiece-mute --ez on true
adb shell am broadcast -n $R --es op sip-stack --es stack pjsua2
adb shell am broadcast -n $R --es op default-sim --es sim system   # SIM without X-VSA-SIMSLOT: system|refuse|<slot>
adb shell am broadcast -n $R --es op sim-inbound --ei slot 0 --ez on false   # leave SIM 0's incoming calls to the phone
adb shell am broadcast -n $R --es op loopback --ez on true   # echo incoming cellular calls (test mode)
adb shell am broadcast -n $R --es op sip-debug --ez on true  # verbose pjsip logging
adb shell am broadcast -n $R --es op provision --ez hold_sip false   # stay registered while not ready
adb shell am broadcast -n $R --es op provision --ei audio_method 2   # 0 Automatic, 1 Call redirection, 2 Direct telephony
adb logcat -s VoiceSipAgent VsaPrivileged VsaTap
```

Replies use `result=1` for acknowledgement, `2` for errors, and `3` for a five-second timeout.
Acknowledgement does not guarantee completion; changes may wait until calls end. Check logcat or the
in-app log before retrying. `result=0` with no data is not an acknowledgement: check the component name.
Unknown arguments and wrong types are rejected; use the extra types shown in the examples.

Phone numbers are logged with only their last two digits; call audio is never logged or stored.

## Troubleshooting

- **The far end cannot hear you, or you hear silence:** set **Cellular audio** to *Direct telephony* and turn
  **Mute call audio on this phone** off (`--ei audio_method 2` and `earpiece-mute --ez on false` over adb). This was the
  only combination that gave two-way audio on the community-tested devices above.
- **Calls on one SIM drop a few seconds after answering, logging `not in a cellular call: audio mode=3`:** that
  SIM's calls are handled as VoIP by a carrier app (e.g. Google Fi with Wi-Fi calling on a Pixel), so the call audio
  never passes through the modem. Turn off Wi-Fi calling for that SIM.
- **PBX calls without `X-VSA-SIMSLOT` are refused on a dual-SIM phone:** Android has no default SIM for calls
  ("ask every time"). Pick a SIM in the app, choose one in Android's SIM settings, or send the header from the PBX
  (see [Dual SIM](#dual-sim)).
- **Shizuku 13.6.0 on MediaTek / Transsion (e.g. Infinix) phones:** the app's privileged service never
  starts (`NullPointerException` in `LoadedApk.makeApplicationInner`;
  [RikkaApps/Shizuku#1198](https://github.com/RikkaApps/Shizuku/issues/1198)). Install Shizuku 13.5.4 instead.
- **After restarting Shizuku, open the app once** (or tap the gateway notification). A restarted Shizuku server
  may not hand its connection to an app that is already running (seen with Shizuku 13.6.0 on Android 17); it does
  so when the app comes to the foreground. Until then the gateway reports *Shizuku not running* and, by default,
  stays unregistered. Over adb: `adb shell monkey -p io.github.sparkcyf.voicesipagent -c android.intent.category.LAUNCHER 1`.
- **Vendor setup:** Xiaomi, POCO and Redmi need a signed-in Mi account before USB installation and USB debugging
  (security settings) can be enabled, and HyperOS may refuse `pm grant`. Infinix and Redmi may block
  `adb install`; copy the APK to the phone and install it from the file manager. On Samsung, turn off
  **Auto Blocker**. Watch for compatibility changes with major Android version upgrades.

## Call routing

The gateway joins the PBX as a **trunk**, not an extension; all routing lives in the PBX's inbound/outbound
routes.

- **Cellular inbound**: the phone sends `INVITE sip:<target>@<domain>`, where `target` is a DID (by default the
  trunk's own user name), with the caller number in `P-Asserted-Identity`. An Inbound Route on the PBX sends the
  DID wherever you like.
- **SIP outbound**: the number to dial is taken from the `X-VSA-Dial` header, falling back to the Request-URI user
  part (`PJSIP/$OUTNUM$@<trunk>` on a direct LAN connection). The PBX gets 200 OK once the cellular call is active.
  - With a registrar-routing proxy in between (Flexisip etc.), the Request-URI user part must stay the gateway's
    own AoR, otherwise the proxy looks up the number in its registrar and answers 404 — so the number goes in
    `X-VSA-Dial` (see the dial plan below).

### Dual SIM

One trunk serves every SIM; the `X-VSA-SIMSLOT` header names the SIM by Android's **logical slot index**
(0, 1, … as in `SubscriptionInfo.getSimSlotIndex()`; two eSIM profiles active on one chip are two slots).
`adb … --es op status` lists them as `sims=0:<carrier>(sub<id>),1:…`.

- **Cellular inbound**: the INVITE carries `X-VSA-SIMSLOT` with the slot the call arrived on; read it in the
  dial plan with `${PJSIP_HEADER(read,X-VSA-SIMSLOT)}` if the SIMs need different destinations. The app's
  **Take over incoming calls on SIM n** switches (all on by default) leave a SIM's incoming calls ringing on the
  phone as usual, e.g. to bridge only a secondary SIM. They do not affect outbound calls.
- **SIP outbound**: `X-VSA-SIMSLOT` selects the SIM. Without it, the app setting **SIM for PBX calls without
  X-VSA-SIMSLOT** decides: *System* (default) follows Android's default SIM for calls and refuses the call if
  Android asks every time; *SIM n* always uses that slot; *Refuse* requires the header. A slot with no active SIM,
  or a call Telecom places on another SIM, gets `480` (there is no fallback to another SIM); a malformed value gets
  `400`. The phone never shows Android's SIM picker for gateway calls.
- Only one call is bridged at a time, whichever SIM it uses.

### PBX example (FreePBX / IssabelPBX with chan_pjsip)

`pjsip_custom_post.conf`:

```ini
[android-gw]
type=endpoint
transport=transport-udp
context=from-trunk          ; inbound calls go through Inbound Routes, no internal-extension privileges
aors=android-gw
auth=android-gw-auth
identify_by=username
disallow=all
allow=ulaw,alaw
dtmf_mode=rfc4733
direct_media=no
rtp_symmetric=yes
force_rport=yes
rewrite_contact=no          ; behind Flexisip the proxy already rewrote the Contact
media_encryption=sdes       ; pairs with SRTP "optional"; plain RTP still works on a LAN
media_encryption_optimistic=yes
trust_id_inbound=yes        ; trust the gateway's P-Asserted-Identity as caller ID

[android-gw-auth]
type=auth
auth_type=userpass
username=android-gw
password=<secret>

[android-gw]
type=aor
max_contacts=1
remove_existing=yes         ; take over the registration as soon as the phone changes IP/port
qualify_frequency=60
```

`extensions_custom.conf` (keeps the Request-URI on the gateway's AoR and puts the number in a header):

```ini
[vsa-android-gw-out]
exten => _X.,1,Dial(PJSIP/android-gw,,b(vsa-android-gw-hdr^s^1(${EXTEN})))
exten => _X.,n,Hangup(${HANGUPCAUSE})

[vsa-android-gw-hdr]
exten => s,1,Set(PJSIP_HEADER(add,X-VSA-Dial)=${ARG1})
exten => s,n,ExecIf($["${ARG2}" != ""]?Set(PJSIP_HEADER(add,X-VSA-SIMSLOT)=${ARG2}))
exten => s,n,Return()

; Dual SIM: one context per slot (see Dual SIM above)
[vsa-android-gw-out-sim0]
exten => _X.,1,Dial(PJSIP/android-gw,,b(vsa-android-gw-hdr^s^1(${EXTEN},0)))
exten => _X.,n,Hangup(${HANGUPCAUSE})

[vsa-android-gw-out-sim1]
exten => _X.,1,Dial(PJSIP/android-gw,,b(vsa-android-gw-hdr^s^1(${EXTEN},1)))
exten => _X.,n,Hangup(${HANGUPCAUSE})
```

Web UI:

- **Trunk**: Custom, dial string `Local/$OUTNUM$@vsa-android-gw-out` (on a direct LAN connection without a proxy,
  `PJSIP/$OUTNUM$@android-gw` also works).
- **Outbound Route**: a dedicated prefix (e.g. `9#7#`) with pattern `X.` using that trunk; it must be ordered
  before broader routes such as `9.`.
- **Dual SIM**: one custom trunk per slot (`Local/$OUTNUM$@vsa-android-gw-out-sim0`, `…-sim1`) and one route
  prefix per trunk, e.g. `9#7#` → slot 0 and `9#6#` → slot 1.
- **Inbound Route**: DID `android-gw` → the destination of your choice.

### Reaching the PBX over the internet: Flexisip

The phone registers over TLS to [Flexisip](https://www.linphone.org/en/flexisip-sip-server/)
(`sips:<domain>:5061`), which forwards requests to Asterisk (e.g. over WireGuard) and relays media with
MediaRelay (SRTP stays end-to-end between the phone and Asterisk). Flexisip needs no gateway-specific
configuration:

- REGISTER is authenticated by Asterisk (Flexisip authentication off, `reg-on-response=true`).
  `ContactRouteInserter` rewrites the Contact to point at Flexisip and NatHelper fixes the NAT address, so incoming
  calls arrive over the TLS connection the phone already opened — no open ports on the phone.
- Requests from Asterisk are routed by Request-URI user part, hence the `X-VSA-Dial` dial plan above.
- Phone settings: `server = domain = <flexisip domain>`, `port = 5061`, `transport = tls`, `srtp = optional`.

## Building from source

Requirements: JDK 17+, Android SDK with NDK, `swig` (e.g. `brew install swig`).

```bash
ABIS="arm64-v8a x86_64" APP_PLATFORM=android-30 sip-pjsua2/native/build-pjsua2.sh   # once; outputs in git-ignored src/generated/
./gradlew :sip:test :gateway:test   # JVM unit tests
./gradlew :app:assembleDebug
```

The default native build targets arm64-v8a. Match native ABIs to the app's Gradle `abiFilters`.

The script builds OpenSSL, libopus, and PJSIP from pinned, SHA-256-verified sources.
See [native dependencies](TECHNICAL.md#native-dependencies) for build layout and rebuild behavior.

## Architecture

| Module | Type | Responsibility |
|---|---|---|
| `:voice-api` | JVM | Contract: `CallLeg` / `CallEndpoint` / `MediaPort` (8/16 kHz mono PCM16, 20 ms frames) / `HangupCause` |
| `:sip` | JVM | Built-in SIP UA: UDP, transactions, digest auth, REGISTER, INVITE/CANCEL/BYE/re-INVITE, SDP, RTP + jitter buffer, G.711, RFC 4733 |
| `:gateway` | JVM | `CallBridge` state machine (bridges any two `CallEndpoint`s), `MediaBridge` (single clock domain), `EchoEndpoint` |
| `:telephony` | Android | Companion `InCallService` + `CellularEndpoint`: Telecom calls as `CallLeg`s |
| `:privileged` | Android | Shizuku UserService (AIDL): setup/health, call-audio tap over a SOCK_SEQPACKET socketpair |
| `:sip-pjsua2` | Android | pjsua2-backed `CallEndpoint`: null sound device + `AudioMediaPort`, pjsua never touches Android audio |
| `:app` | Android | Runtime wiring, foreground service, settings (Keystore-sealed password), adb control, Compose UI |

To plug in another voice/control backend, implement a `CallEndpoint` (with its `CallLeg` / `MediaPort`) and hand it
to `CallBridge`; the gateway core does not change. See [AGENT.md](AGENT.md) for design notes and the pitfalls
found along the way.

## References

- [AirSIM](https://github.com/kai-wu-cortex/AirSIM) — reading its code was the starting point for the call-audio
  interception approach; this project is an independent implementation and does not depend on it.
- [Shizuku](https://github.com/RikkaApps/Shizuku), [pjsip](https://github.com/pjsip/pjproject),
  [Flexisip](https://gitlab.linphone.org/BC/public/flexisip).

## License

Copyright (C) 2026 sparkcyf

This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation; either version 2 of the License, or (at your option) any later version. See [LICENSE](LICENSE). As a special exception, linking with any library licensed under the Apache License, Version 2.0 is permitted, provided all other code complies with the GPL.

Release APKs statically link pjsip (GPL-2.0-or-later), OpenSSL (Apache-2.0) and libopus (BSD-3-Clause),
and bundle Apache 2.0-licensed Java/Kotlin components (AndroidX/Jetpack, Kotlin, kotlinx.coroutines, and Shizuku API/provider); see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The corresponding source for every release is the tagged commit
of this repository plus the pinned upstream tarballs fetched by `sip-pjsua2/native/build-pjsua2.sh`.

## Disclaimer

THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE. You use it entirely at your own risk.

In particular:

- **Not for emergency calls.** Emergency numbers are refused by design. Do not rely on this gateway, or on a phone
  running it, to reach emergency services.
- **Calls may fail.** Calls can be dropped, rejected, misrouted or answered without audio, and an OS update can
  break the app at any time. Missed calls and their consequences are your responsibility.
- **Charges.** Calls placed through the gateway are billed by your carrier. Restrict dial-out prefixes and secure
  your PBX; a compromised PBX can make the phone dial premium-rate numbers.
- **Laws and terms of service.** Relaying, recording or intercepting calls, and connecting a mobile line to a PBX,
  may be restricted by law or by your carrier's terms in your jurisdiction. You are solely responsible for
  complying with them and for obtaining any consent required from the parties to a call.
- **Security.** Shizuku grants this app shell-level privileges. Only install builds you trust, and keep your SIP
  credentials and PBX secure.

This project is not affiliated with or endorsed by Google, Motorola, the Shizuku, pjsip, Asterisk or Flexisip
projects, or any carrier.

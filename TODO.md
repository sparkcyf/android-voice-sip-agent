# TODO

Follow-ups to the current gateway implementation. These items describe planned work, not supported features.

## Manual dialer fallback

- [ ] Add incoming answer/reject controls, an ongoing-call screen with hang-up, simple number entry and dial-intent handling, and an in-call DTMF keypad.
- [ ] Keep manual calls on normal handset audio, independent of SIP and Shizuku. Explicitly track manual ownership so gateway reconnection never claims, mutes, or taps these calls.
- [ ] Restore the UI from current Telecom calls after service reconnection or process restart. Preserve system emergency-call handling and the switch-dialer recovery action.
- [ ] Verify manual calling with SIP disconnected, Shizuku unavailable, and takeover off, including background and lock-screen behavior.

See the [dialer scope and phased fallback](docs/ANDROID_COMPATIBILITY_PLAN.md#dialer-scope-and-phased-fallback) for included controls, ownership rules, validation, and excluded features. Implement this after verifying gateway audio on a physical device.

## Dual SIM support

- [x] Select the outgoing SIM by logical slot (`X-VSA-SIMSLOT`), else the system default calling SIM; refuse when neither exists. Never let Telecom pick or prompt.
- [x] Report the slot of incoming calls in `X-VSA-SIMSLOT`.
- [x] Configure which SIMs' incoming calls are taken over (per-SIM switches); other calls stay with the phone app.
- [ ] Detect a different SIM/eSIM profile in a slot (slot indices stay the same) and require explicit confirmation; removed slots already refuse with 480.
- [ ] Verify simultaneous calls on both SIMs (Pixel 11 Pro reports IMS simultaneous calling).
- [ ] Integrate account selection with manual dialer fallback and validate concurrent-call behavior across SIMs. Selecting two accounts does not imply support for two simultaneous audio bridges.

See the [fallback account-selection requirements](docs/ANDROID_COMPATIBILITY_PLAN.md#dialer-scope-and-phased-fallback) and [call/audio ownership requirements](docs/ANDROID_COMPATIBILITY_PLAN.md#changes-needed-for-the-compatibility-port). The current [status](README.md#status) describes the single-call limit.

## Audio diagnostics

- [ ] Extend existing diagnostics with the effective audio backend/construction path, requested and observed devices, PCM format, and last open/start/read/write or restoration failure.
- [ ] Expose available SIP packet-loss/jitter metrics and media-buffer underrun/overrun counters, distinguishing measured values from unavailable data.
- [ ] Add a guided two-way audio check that separately verifies captured downlink, remote receipt of injected uplink, and handset mute/restoration. Successful construction, routing setters, and frame counters are not proof of remote delivery.
- [ ] Keep diagnostics under Advanced settings and preserve privacy: no PCM recording, passwords, or unmasked phone numbers in diagnostic output.

See [Advanced settings and diagnostics](docs/ANDROID_COMPATIBILITY_PLAN.md#settings-and-defaults), the [audio backend and routing checks](docs/ANDROID_COMPATIBILITY_PLAN.md#3-implement-the-older-cellular-audio-backend), and the [HAL error-visibility audit](docs/ANDROID11_SOURCE_AUDIT.md#hal-calls-and-observable-errors).

## Call waiting and hold

- [ ] Define and verify the policy for a second incoming, manual, or cross-SIM call while a gateway call is active. Preserve ownership and prevent audio from reaching the wrong call.
- [ ] Add safe hold/resume behavior, checking Telecom capabilities and audio eligibility before reopening a tap. Restore owned mute/routing state on hold, failure, and termination.
- [ ] Implement SIP hold synchronization where supported, including hold music and resume without overlapping audio handles or changing the modem-driven clock.
- [ ] Test remote and local hold, repeated hold/resume, call waiting, helper loss while held, and call/service termination during pending audio opens.

See [call/audio ownership and cleanup](docs/ANDROID_COMPATIBILITY_PLAN.md#changes-needed-for-the-compatibility-port), the [fallback scope](docs/ANDROID_COMPATIBILITY_PLAN.md#dialer-scope-and-phased-fallback), which defers advanced hold/switching, and the [current validation gaps](README.md#status).

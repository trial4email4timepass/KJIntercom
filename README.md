# KHALSA Intercom (Android) — v2.3

A family walkie-talkie for Android phones.

* **📢 Public Telecast / 🔒 Private** — live push-to-talk to phones on the **same Wi-Fi**.
* **🌍 AWAY — long distance** — voice messages and chat to family phones **anywhere**, on
  **mobile data or Wi-Fi**. Hold TALK, speak, release: the message plays out loud on the
  other phone by itself.
* **🌍 Public Broadcast** — record a voice note or type a message and share it to any app.

## What changed in v2.3 (mobile data fix)

In v2.2, AWAY mode streamed live audio in 100 ms pieces through one public relay and played
each piece the moment it arrived. On mobile data the pieces arrived, so the app showed the
other person "speaking", but no sound came out. v2.3 changes how AWAY voice travels:

1. Your whole message is recorded (up to 30 s), quiet audio is boosted, and it is compressed
   (IMA ADPCM, 16 kHz, 8 KB per second).
2. It is encrypted with the family code, split into 24 KB parts and sent on **two independent
   relays at once** (`broker.emqx.io`, `test.mosquitto.org`), so both phones always share one.
3. The other phone plays it **only after every part has arrived**, so mobile-data delays cannot
   chop or silence it. If its owner is talking, it waits and plays right after.
4. The other phone answers **✓ delivered** and **✓✓ played**; the sender resends once if no
   answer comes back.

Other fixes:

* The phone is no longer forced into "in call" audio mode the whole time the app runs (this
  re-routed or muted media audio on many phones and hijacked the volume keys and Bluetooth).
* Chat over AWAY no longer drops the relay connection (it was sent from the main thread, which
  Android blocks); same-Wi-Fi chat now actually leaves the phone for the same reason.
* Dead relay connections are noticed in seconds instead of up to 90 s.
* If the app starts without Wi-Fi, same-Wi-Fi listening now starts when Wi-Fi comes back.
* A message that arrives while media volume is 0 shows a warning.
* If the other phone still runs v2.2, the app tells you to update it.

**Install v2.3 on both phones.** It installs over v2.2 and keeps your family code and names.

## Build the APK

Push to GitHub: the **Build APK** workflow runs the unit tests, builds the APK and publishes it
as a release (`latest` from `main`, `v2.3` from the fix branch). Open **Releases** on your phone
and tap **KHALSA-Intercom.apk**.

Or in Android Studio: File → Open this folder → Build → Build APK(s).

Both sign with `app/debug.keystore` (the same key as v2.2) so updates install in place.

## Project layout

| File | Purpose |
|---|---|
| `IntercomService.kt` | Always-on foreground service: same-Wi-Fi multicast, talk, chat, UI state |
| `AwayEngine.kt` | AWAY mode: relays, presence, voice messages, receipts, playback |
| `MainActivity.kt` | The whole UI (built in code) |
| `core/ClipProtocol.java` | Voice-message parts, reassembly and receipts |
| `core/ImaAdpcm.java` | Audio compression |
| `core/AwayProtocol.kt` | Family code, AES-256-GCM encryption, replay protection |
| `core/IntercomProtocol.kt` | Same-Wi-Fi packet format and peer list |
| `net/MqttClient.kt`, `net/MqttWire.kt` | Small TLS MQTT client |
| `Helpers.kt` | Saved name, id, family code; notification channel |
| `BroadcastFileProvider.kt` | Shares Public Broadcast recordings |

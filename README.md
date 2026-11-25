# KJ Intercom (Android)

A two-way, push-to-talk intercom for two Android phones. Hold the button and speak; when you let go, the
message **plays out loud on the other phone automatically**. Nobody has to touch the phone to hear it.
Works over **Wi-Fi and mobile data**, even when the phones are on different networks.

## How it works

```
Phone A ──(encrypted voice clip)──► MQTT relay (internet) ──► Phone B ─► plays it automatically
Phone A ◄──────── "✓✓ Played" ◄─────────────────────────────── Phone B
```

* A background service keeps one small connection open to a relay server (an MQTT broker; the default is the free public `broker.hivemq.com`).
* Both phones use the same **pair code**. The code picks a private channel on the relay **and** is the
  encryption key (AES-256-GCM). The relay only passes along scrambled bytes it can't decode.
* Clips are AAC, up to 30 seconds, about 4 KB per second, so they're cheap on mobile data.
* After the other phone plays your message you see **✓✓ Played on <name>**.
* If a phone is briefly offline, the relay holds its messages and delivers them when it reconnects.
* The intercom turns back on by itself after a reboot.

## Build the APK

### Option A: GitHub (no Android Studio needed)
1. Create a new GitHub repository and upload this whole folder (keep the `.github` folder).
2. Open the repo's **Actions** tab. The **Build APK** workflow runs automatically (about 5 minutes).
3. Open the repo's **Releases** → **latest** and tap **KJIntercom.apk** (works right from your phone).

### Option B: Android Studio
1. File → Open → choose this folder. Let Gradle sync.
2. Build → Build App Bundle(s) / APK(s) → **Build APK(s)**.
   The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Both options sign with the included `app/debug.keystore`, so new builds install over old ones.
(That key is for personal use. Make your own key before publishing on Google Play.)

## Set up the two phones
1. Install the APK on both phones (allow "Install unknown apps" when asked).
2. Open the app on **Phone A**. It already shows a pair code. Type a name like "Kitchen" and tap **Save & connect**.
3. On **Phone B**, type the **same pair code** (or use **Share code** on Phone A to send it), type a name, then tap **Save & connect**.
4. Allow **Microphone** and **Notifications** on both phones.
5. Tap **Allow running in background** on both phones and approve it.
   * **Xiaomi, Redmi, Oppo, Vivo, Realme, OnePlus, Samsung:** also open the app's settings, set Battery to
     **Unrestricted / No restrictions**, and turn on **Autostart**. Without this, these phones may cut the
     connection when the screen is off.
6. The status dot turns green ("Connected – listening"). Hold the big button on one phone and talk.

## Tips
* Keep **Stay connected while screen is off** turned on for the most dependable intercom. It holds a light wake lock, which uses some extra battery.
* The message plays through the phone's speaker at **media volume**, so turn media volume up.
* Messages that arrive while you're holding the talk button wait and play when you let go.
* **Reliability upgrade:** the public broker is free but shared and comes with no guarantee. For a private relay, create a free
  HiveMQ Cloud cluster and enter its address (for example `ssl://xxxx.s1.eu.hivemq.cloud:8883`), username and
  password under **Advanced** on **both** phones.

## Project layout
| File | Purpose |
|---|---|
| `IntercomService.kt` | Always-on foreground service: relay connection, receive → auto-play, send, acks |
| `AudioPlayer.kt` | Queued auto-playback with an intercom chime |
| `VoiceRecorder.kt` | Push-to-talk AAC recording (30 s max) |
| `Packet.kt` | Message format + AES-GCM encryption derived from the pair code |
| `MainActivity.kt` | UI: hold-to-talk, pairing, history and replay, settings |
| `BootReceiver.kt` | Turns the intercom back on after a reboot |

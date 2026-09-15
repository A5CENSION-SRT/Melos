# Melos

A small Android app that mutes Spotify automatically when an ad starts playing, and restores volume once your music is back.

## How it works

Melos runs a lightweight `NotificationListenerService` that watches Spotify's active media session. When the track metadata looks like an ad, it mutes the music stream; when the ad ends, it restores volume after a short debounce (so back-to-back ads don't cause flicker).

## Features

- Automatic mute/unmute during Spotify ads
- Live status card (listening / muted / no session)
- Today and all-time ad-mute counters
- One switch to turn muting on or off

## Setup

1. Install the app.
2. Grant notification access when prompted (required to detect Spotify's media session).
3. Play Spotify — Melos does the rest.

## Building

```bash
./gradlew installDebug
```

Requires JDK 11+ and the Android SDK.

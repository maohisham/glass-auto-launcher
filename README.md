# Glass Auto

**A car launcher for Android head units and car boxes — split map + media, glass dock, synced lyrics, GPS speed.**

Based on [glass-tv-home](../glass-tv-home) glass UI components.

```
┌──────────────────────┬──────────────────────┐
│                      │  Now Playing         │
│   Your map app       │  Artist · Track      │
│   (freeform window)  │  ──────────────────  │
│                      │  Lyrics scroll       │
│   ← drag divider →  │  line by line        │
│                      │                      │
├──────────────────────┴──────────────────────┤
│  [Maps]  [Music]  [Phone]  [Settings]       │  glass dock
└─────────────────────────────────────────────┘
```

## Features

- **Any map app** — Google Maps, Waze, HERE, OsmAnd: pick one in Settings, it launches into the left panel
- **Resizable split** — drag the centre divider; ratio is saved and restored on next launch
- **Live media controls** — plays/pauses/skips any media app via MediaSession (Spotify, YouTube Music, etc.)
- **Synced lyrics** — fetched from [LRCLib](https://lrclib.net) (free, no API key), line highlighted in real time
- **GPS speed** in km/h or mph on the status bar
- **Glass dock** — full-width bottom dock, customisable apps, glass highlight from glass-tv-home
- **Config via JSON** — push `config.json` to the device, no rebuild needed

## Requirements

- Android 8.0+ (API 26+)
- Freeform window mode enabled on the device (most car boxes have it on; enable via adb if not):
  ```bash
  adb shell settings put global enable_freeform_support 1
  adb shell settings put global force_resizable_activities 1
  ```

## Setup

```bash
# 1. Build and install
CAR_HOST=192.168.0.xxx ./build-and-install.sh

# 2. Grant permissions (one-time)
adb shell pm grant com.glass.auto android.permission.ACCESS_FINE_LOCATION
adb shell cmd notification allow_listener com.glass.auto/.MediaListenerService

# 3. Push config
adb push config.json /sdcard/Android/data/com.glass.auto/files/config.json
```

## Config keys (`config.json`)

| Key | Default | Description |
|---|---|---|
| `map_package` | `""` | Package name of map app (e.g. `"com.google.android.apps.maps"`) |
| `split_ratio` | `0.6` | Map panel width fraction (0.25 – 0.82) |
| `media_package` | `""` | Preferred media app package (empty = auto-detect active session) |
| `show_lyrics` | `true` | Show synced lyrics panel |
| `show_speed` | `true` | Show GPS speed on status bar |
| `speed_unit` | `"kmh"` | `"kmh"` or `"mph"` |
| `dock_apps` | `[]` | Package names for dock (left to right) |
| `dock_auto_hide` | `false` | Auto-hide dock after a few seconds |
| `reduced_motion` | `false` | Snap glass transitions instead of animating |

## Architecture

```
CarActivity          — main launcher, owns all UI except the map window
  SplitPanelLayout  — draggable split; measures/positions map + media panels
  FreeformLauncher  — launches any app into a Rect via ActivityOptions.setLaunchBounds()
  MediaSessionController — reads track/playback from active MediaSession
  LyricsProvider    — fetches LRC lyrics from LRCLib, parses timestamps
  SpeedMonitor      — GPS speed via LocationManager
  DockView          — custom glass dock drawn in Canvas
  Config            — reads/writes config.json from external files dir

Glass UI (from glass-tv-home):
  GlassView         — selection highlight with layered bloom
  GlassDrawable     — blurred-wallpaper glass panel
  GlassRim          — rim shine
  Glider            — spring-animated highlight
  Backdrop          — blurred wallpaper bitmap manager
```

## Roadmap

- [ ] Full-screen map mode (collapse media panel to bottom strip)
- [ ] OBD-II speed via Bluetooth ELM327 adapter
- [ ] Car avatar / vehicle status panel
- [ ] Full app-picker grid in Settings (AppPickerActivity)
- [ ] Rounded-corner overlay mask on map panel edges
- [ ] Waze deep-link for turn-by-turn navigation intent
- [ ] Dark/light status bar theme

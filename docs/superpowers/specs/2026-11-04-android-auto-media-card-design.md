# Glass Auto card for Android Auto: prayer, weather, lyrics and music in one card

Updated 2026-11-05 to match what is built and what has been tested. The first version of this document (2026-11-04) described a prayer bar over an album cover; the design has changed a lot since, so this replaces it. Section "What changed since the first version" lists the reversals.

## Intent and why

The in-car launcher (split screen on the Eonon UX7ST head unit and Carlinkit Ultra 2 box) is too much hassle to keep working across ROMs: split screen depends on firmware, an accessibility service, and adb that the Eonon does not allow.

The user runs Android Auto from a **vivo X300 on Android 16**, wirelessly through a **Carlinkit Ultra 2 (AutoKit app)**. The new product is a **phone app that appears inside Android Auto** as a media app, modelled on AutoDeck 2.0 (prayer countdown, synced lyrics and weather inside Android Auto). The launcher is kept as is and is not part of this.

## What the user decided

- Google Maps stays visible at all times; Android Auto's own Maps stays in the main area. We do not embed, mirror or replace it. The user can resize it, so the split varies between about 30:70 and 70:30.
- We are a **media-category Android Auto app**. Our only place on the car screen is the **media card** beside Maps. **One card, not two**: prayer, weather, lyrics and controls are all in it.
- The card **follows whatever app is playing** (YouTube Music, VRadio, Bitchord, any app with a media session), shows its lyrics, and forwards play, pause, next, previous, seek and voice search to it. It never plays audio itself.
- **Shuffle on by default** in the playing app, asked once per app per session.
- **Switching player** from the card's browse list, for example YouTube Music to Bitchord or VRadio.
- **Recent songs and Most played** lists in the browse list, kept by this app.
- The prayer and weather data are **real** (JAKIM zone for the phone's position, Open-Meteo), and they **update by themselves when the user travels**.
- **12-hour time format**.
- **A prayer alert when each prayer time arrives**, in Malay: `Masuk waktu <prayer> <prayer time> bagi kawasan <location name>`, for example `Masuk waktu Zohor 1:10 PM bagi kawasan Petaling Jaya`. It is shown on the car screen whichever app is on it, as far as Android Auto allows.
- The user asked for the YouTube Music phone screen itself to be shown in the car. Not possible from a media card; see non-goals.

## The card layout

Android Auto draws the title, artist, progress bar and buttons. We supply **one picture plus the text lines**.

**Expanded card (Maps about 30%, card about 70%).** The picture is split in two halves.
- Left: song and artist, then the lyrics with the line being sung highlighted, over a **faded copy of the album cover** (about 25% strength with a dark layer so the lyrics stay readable).
- Right: the **next prayer and the time until it**, the **weather and city**, then a **prayer table** (Prayer / Time, 12-hour, current prayer marked with a dot, next prayer in green).
- Media buttons: drawn by Android Auto, so where they land is its choice (shown on the left in the mock-up, but unconfirmed).

**Collapsed card (Maps about 70%, card about 30%).** Only text lines survive; Android Auto turns the picture into a blurred background.
- Top line: current prayer and its time, countdown to the next prayer, weather. About 38 characters, so it may be cut off on a narrow card.
- Bottom line: `♪` then the current lyric and the next two lines (a card that wraps shows more of the song; one that does not just shows the first).
- Buttons: Android Auto's.

**Nothing playing / radio / no lyrics.** The left side says "Play something to see lyrics", "Radio: no lyrics" or "No synced lyrics for this track". The prayer side is unchanged. VRadio shows what is playing and no lyrics.

**At prayer time.** For the first 3 minutes after a prayer time the card says "<Prayer> time now" instead of counting down to the next prayer (picture and collapsed top line).

Mock-ups: `docs/mockups/glass-auto-stacked-card.html` (layouts), `docs/mockups/glass-auto-icon.svg` (icon).

## How it behaves

- **Following a player:** every 2 seconds it picks the app that is playing, else keeps the one it already follows. A switch the user made from the list is protected for a few seconds so the old app is not picked again.
- **Staying on our card:** when the player changes track Android Auto tends to jump to the player's own card. The card re-announces itself as freshly playing 3 times in the seconds after a change. This is a best guess and not confirmed.
- **Lyrics:** LRCLIB (free). The title is cleaned first ("(Official Video)", "feat. X", first artist only); if the exact lookup misses it searches and accepts a match within 5 seconds of the song length. Lyric line follows the playback position every 500 ms.
- **Prayer times:** JAKIM e-Solat for the nearest zone, then waktusolat.app, then Aladhan (JAKIM method). The countdown is recomputed every 30 seconds without network.
- **Weather:** Open-Meteo, with a city name from the phone's geocoder.
- **Location and travel:** the phone's GPS (a media card cannot read the car's GPS). Updates are watched while the card runs; after moving more than 5 km, prayer times and weather are refetched (at most every 2 minutes). Backup refresh every 20 minutes. A failed fetch retries after 2 minutes. This needs the user to set the app's Location to "Allow all the time".
- **Browse list (card's browse icon):**
  - Recent songs (last 30), Most played (top 30), Up next (the playing app's queue, if it shares one), Players (switch app).
  - History is stored on the phone only, up to 200 songs, recorded when a song really starts playing.
  - Picking a song asks its app to play it through a search, the same as a voice request. It can pick the wrong version or be ignored by an app that does not support search.
- **Switching player:** pauses the current app, then starts the chosen one. If it has no session it is sent a media Play button, and after 3 seconds its screen is opened on the phone. Android 16 may block this; unconfirmed.
- **Prayer alert:** a timer set to each prayer time (and a 30-second backup check) posts one notification per prayer per day, for Subuh, Zohor, Asar, Maghrib and Isyak (not Syuruk), if the card is running within 3 minutes of the time. It is a message-style notification with Mark as read and Reply actions, because Android Auto only shows that kind and reads it aloud. The sentence is the one above; the title is `<Prayer> · <time>`. The location is the city name from the phone, else the first part of the JAKIM zone name; if neither exists the sentence ends after the time. Reply does nothing. It only works while Android Auto is connected, because the card only runs then.
- **Shuffle and repeat buttons** go to the app. Shuffle is only turned on where the app offers the standard shuffle action.

## Goals

- Installs as a normal phone app and appears in Android Auto as one media card beside Maps.
- Everything on the card is real data and stays correct while driving.
- Works with any music app that publishes a normal media session.

## Non-goals and anti-goals

- No Maps, YouTube or other app screen embedded or mirrored. **Showing the YouTube Music phone screen in the car is out of scope.** Screen-mirroring apps (Screen2Auto, AA Mirror Plus) exist but need unofficial installers and Android Auto developer settings, touch may need root, and Google blocks video and mirroring by default. Not recommended; not built.
- No always-visible second panel. No separate Prayer card or Lyrics card (both tried, merged).
- **No real YouTube Music or Spotify playlists or library**: they refuse third-party browsing. VRadio has no browse service at all.
- Not a Play Store release. A card that flickers or floods the car screen with updates is a failure even if it technically works.
- Must not break the launcher, which is untouched.
- Safe-driving rules: no video, no text entry on the car screen.

## Constraints and facts found by testing

- Android Auto lists a sideloaded app only with the developer "Unknown sources" switch on (Version tapped 10 times). Confirmed working on the user's phone with Carlinkit.
- A third-party app cannot browse YouTube Music or Spotify (connection refused, checked 2026-11-04). The card reads the playing app's media session instead, which needs **notification access** (and "Allow restricted settings" for a sideloaded app on Android 16).
- The **car's GPS is not reachable** from a media card (only template apps get it).
- **One media card is on screen at a time.** The user switches between Glass Auto, YouTube Music and so on from the media list. Cards cannot sit side by side.
- A card shows one picture, a title line and an artist line (album is not shown). Android Auto uses the picture as a **full-size, blurred background** when collapsed, so detail inside the picture is lost there.
- **Android may shrink the picture** (often to about 320 px). The picture is drawn at 640 px. The app logs `picture: drew 640 px, system kept N px` the first time it runs. Unchecked.
- Android Auto only shows alerts for apps that declare notification support in its descriptor; the declaration is added but the alert is unconfirmed.
- On the user's vivo, install **only into the personal profile (user 0)**, never the work profile or private space.
- Wireless adb to the phone drops when it joins the Carlinkit's Wi-Fi; read logs by USB cable.
- Java, Gradle (AGP 8.2.2), minSdk 26, package `com.glass.aaspike` (code in the sibling project `glass-auto-spike`, still named as a spike).

## Status

Built and installed on the vivo X300 (version 0.3-card, with icon): the single card, real prayer and weather, lyrics with cover, travel refresh, shuffle default, player switching, recent and most-played lists, icon, prayer alert and the "time now" state.

**Not verified in the car** (needs a drive):
1. How Android Auto crops, blurs and sizes the picture in each state (a **calibration card** is in the app: a labelled grid plus long ruler text lines; photograph the car screen expanded and collapsed).
2. Whether the card stays in front when the player changes track, and whether the steering-wheel next button keeps it there.
3. Whether the collapsed top line is too long.
4. **Whether the prayer alert shows in Android Auto** (the first test, before the notification declaration was added, showed nothing; the declaration is a guess at the cause). Test button in the app: "Prayer alert, real format, in 20 s". Fallbacks if it still shows nothing: other notification styles, or only the "time now" state on the card.
5. Whether shuffle can be set (depends on each app), whether player switching starts an app that is not running, whether picking a recent song plays the right one.
6. Real prayer times against the user's home-screen widget (Zohor and Maghrib especially).
7. Battery and performance: not measured. Main cost is location; the card redraws every lyric line (3 to 5 seconds) and every 30 seconds.

**Known gaps (not built):**
- **No auto-resume of the last played song** when the card opens (asked for in the first design).
- No settings screen. The player list is built from installed media apps, plus VRadio.
- Voice requests are forwarded to the current player; the phrase "play X on Glass Auto" is not specially handled.
- The Carlinkit connection still fails sometimes ("connecting" then "waiting"); the cause is not found. To investigate: phone on USB, record the log during an attempt.

## What changed since the first version

| First version | Now | Why |
|---|---|---|
| Prayer bar on top of the album cover, one picture | Two-half picture, faded cover behind lyrics | User asked; the bar was lost in Android Auto's blur when collapsed |
| Possibly a separate Prayer card and Lyrics card | One card | User asked to combine |
| Browse the real app's library **and** next/previous/voice | Next/previous/voice, own history, queue | Apps refuse third-party browsing |
| Follow YouTube Music and VRadio only | Any app with a media session | User also uses Bitchord |
| Phone location only | Phone GPS live, with travel refresh | User wanted car GPS; not possible, this is the nearest |
| Countdown once a minute | Every 30 seconds | Smoother, still cheap |

## Calls I made (for the user to check)

- Collapsed top line drops the weather's city and shows current prayer, countdown and temperature.
- Prayer times are in 12-hour format without AM/PM only inside the long collapsed line (to save space).
- Re-announcing the session after a track change is a best guess at keeping the card in front.
- History keeps 200 songs and shows 30.
- Travel refresh at 5 km, no more often than every 2 minutes.
- Alerts for the five prayers only, not Syuruk; none more than 3 minutes late; the time appears both in the sentence and in the notification title (the title could drop it).
- The card's own text ("time now", "Loading prayer times") is English; only the alert is Malay.

## Next steps

1. Drive test with the calibration card and the checks above; send photos and the app log.
2. Fit the text and layout to what the car really shows (picture shape, line lengths).
3. Auto-resume of the last song if still wanted; a Malay version of the card's own text if wanted.
4. Move the code out of the throwaway-named project into a proper app, then write an implementation plan (writing-plans) before large new features.

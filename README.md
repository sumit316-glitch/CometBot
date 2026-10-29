# Comet Bot

Auto-plays the meteor-tapping mini game: taps the **orange comets** (pink/orange tail + white head),
never taps the **skull meteors**.

## Get the APK (no Android Studio needed)
1. Create a free GitHub account, make a new repository, upload everything in this folder
   (keep the `.github/workflows/build.yml` path).
2. Open the repo > **Actions** > "Build APK" > wait ~4 min > download artifact **CometBot-apk** (zip, contains app-debug.apk).
3. Install the APK on your phone (allow "install unknown apps" when asked).
Alternative: open this folder in Android Studio and press Run.

## Use
1. Open **Comet Bot**. Tap **1. Allow overlay** and switch it on.
2. Tap **2. Open Accessibility settings** > Comet Bot > switch ON (needed to perform taps).
3. Tap **3. Start floating button** and accept the screen-capture prompt.
4. Open the game. Tap the floating bubble: **ON** = playing (shows tap count), **OFF** = paused. Long-press bubble = quit.

## Tuning (app/src/main/java/com/cometbot/)
- Taps land late / early: BotService.kt `* 0.09f` (lead time in seconds) and `0.08f * c.h` (aim point on the comet).
- Skull safety radius: `1.25f * res.skullR`.
- Colours / shapes: Detector.kt (thresholds are relative to a 480px-wide capture).
Tested on screen recording frames of the game; not on a real device.

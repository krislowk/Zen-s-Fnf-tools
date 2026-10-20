# USE THE APK, I WILL BE UPDATING THE MAIN BRANCH SOON, THE MAIN BRANCH IS OUTDATED!!

# THIS IS ONLY FOR PSYCH ENGINE MODDERS!!

# Zen FNF Toolkit

An all-in-one Android toolkit for Friday Night Funkin' / Psych Engine modders. Everything runs on your phone: sprites, icons, audio and song folders, with a bottom navigation bar (**Tools** and **Settings**) and a theme you can customize.

> Built for mobile modders. No PC needed.

---

## Tools

| Tool | What it does |
|---|---|
| **Spritesheet Converter** | Pick frame PNGs, rename them (including bulk rename), and pack them into one spritesheet PNG plus a `TextureAtlas` XML. |
| **Icon Creator** | Combine two images into a 300 x 150 health icon. |
| **SpriteSheet Editor** | Load an existing PNG + XML atlas, rename or add frames, and rebuild a fresh atlas with regenerated coordinates. |
| **GIF to Spritesheet** | Pick a GIF, choose how many frames to sample, and pack it the same way as the converter. |
| **Chromatic Scale Generator** | Turn one or more WAV samples into a pitched chromatic scale (see below). |
| **MP3 to OGG** | Convert audio files to Vorbis `.ogg`, the format FNF engines load. |
| **MP3 to FNF Song** | Build a ready-to-use song folder with `Inst.ogg` and optional `Voices.ogg`. |

### Chromatic Scale Generator

A mobile version of the FNF chromatic scale generator from GameBanana.

- Pick one or more **WAV** samples. They are sorted by file name (natural order, so `2.wav` comes before `10.wav`) and take turns across the range.
- Every note in the range is re-pitched to a fixed pitch, keeping the sample's length and vowel color (constant-pitch TD-PSOLA, like Praat's "replace pitch tier").
- Options: **starting note**, **octave (2 to 4)**, **range in semitones**, **gap between samples**, **Pitched** (turn off to just join the samples) and **Dump individual pitched samples**.
- Output: `chromatic.wav`, plus a `pitched_samples/` folder if you dumped the samples.

### MP3 to OGG

- Pick one or more audio files (MP3, WAV, M4A and more).
- Choose quality: **Small (q3)**, **Balanced (q5)** or **High (q8)**.
- Each file is saved as `<name>.ogg` (44.1 kHz Vorbis).

### MP3 to FNF Song

1. Type a folder name, for example `Songy`.
2. Pick your song. It is converted and saved as **`Inst.ogg`**.
3. Optionally add **Voices**. It is converted and saved as **`Voices.ogg`**.

```
songs/
└── Songy/
    ├── Inst.ogg
    └── Voices.ogg   (optional)
```

---

## Settings

**Theme**
- Follow the system light/dark theme, or choose dark/light yourself.
- Custom colors: accent, background, cards and text, with a built-in color picker and a reset button.
- Custom background image with adjustable dimming.

**Background music**
- A mini player with a music icon, song name, status, seek bar and time.
- Play / pause, stop, back and forward 10 seconds, and a loop toggle.
- Volume slider and a "play music while the app is open" switch.
- The music pauses when you leave the app and stays paused if you paused it yourself.

**App icon**
- Choose between Blue, Purple, Red, Green and Light. Android's themed (monochrome) icons are supported. Your launcher may take a moment to refresh.

**About**
- Shows the app version and build number.

---

## Where files are saved

Everything goes to `/storage/emulated/0/ZFNFTSaves`:

| Tool | Location |
|---|---|
| Spritesheet / Icon / GIF / Editor | `ZFNFTSaves/` |
| MP3 to OGG | `ZFNFTSaves/ogg/` |
| MP3 to FNF Song | `ZFNFTSaves/songs/<folder name>/` |
| Chromatic Scale Generator | `ZFNFTSaves/chromatic_<date_time>/` |

The first time you save, the app sends you to the system "All files access" screen. Allow it so the app can write to that folder.

---

## Building

**GitHub Actions (easiest):** push this repo and the workflow in `.github/workflows/build.yml` builds a debug APK on every push to `main`. Download the `ZenFNFToolkit-debug` artifact from the Actions tab.

**Locally (Android Studio or command line):** you need JDK 17 and the Android SDK with platform 35.

```
gradle wrapper --gradle-version 8.7   # only if the wrapper is missing
./gradlew assembleDebug
```

The APK ends up at `app/build/outputs/apk/debug/app-debug.apk`.

**Termux:**

```
pkg install openjdk-17 gradle aapt2
echo "sdk.dir=/path/to/your/android-sdk" > local.properties
echo "android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2" >> gradle.properties
yes | sdkmanager "platforms;android-35"
termux-wake-lock
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug --console=plain
```

The first build downloads Gradle and FFmpeg and can take a while on a phone.

If installing the new APK fails because an old copy is installed (for example one signed with a different key), uninstall the old app first.

---

## Tech notes

- Kotlin, `minSdk 30`, `compileSdk 35`, `targetSdk 34`.
- Packing runs through WorkManager with a foreground notification. The PNG/XML pair is written through temporary files with rollback backups.
- Audio conversion uses [`dev.ffmpegkit-maintained:ffmpeg-kit-audio`](https://central.sonatype.com/artifact/dev.ffmpegkit-maintained/ffmpeg-kit-audio) (a maintained continuation of FFmpegKit, LGPL), because Android has no built-in Vorbis encoder.
- Themes: light and dark palettes come from normal resources. Custom colors are applied at runtime on top of them.
- The launcher icon is switched with `activity-alias` entries in the manifest.

## Known limitations

- The spritesheet packer uses simple row (shelf) packing. It is predictable and correct, but not the most space-efficient.
- GIF frame extraction uses `android.graphics.Movie`, which samples the GIF at evenly spaced times instead of exact frame boundaries. Raise the frame count if motion looks choppy.
- SpriteSheet Editor rejects atlases with rotated frames instead of silently corrupting them.
- The Chromatic Scale Generator only reads WAV files. Convert other formats first. It re-pitches to a constant note, so it suits voice-like and instrument samples, not drums.
- Custom colors don't change system dialogs or dropdown popups.

## Credits

- The Chromatic Scale Generator is a mobile reimplementation inspired by the FNF Chromatic Scale Generator on GameBanana. The original is a GPLv3 desktop tool built on Praat; this app uses its own pitch-shifting code.
- Audio conversion by FFmpeg and libvorbis through the FFmpegKit community fork.

# Riptide

Client-side auto-fishing mod for Minecraft 26.2 and Fabric. It can cast and reel a fishing rod, detect bites, and attack catches with a configured weapon. Optional pet switching is available in the settings screen.

## Requirements

- Java 25
- Fabric Loader 0.19.3 or newer
- Fabric API 0.154.2+26.2

## Build

Run `./gradlew build` on macOS or Linux, or `gradlew.bat build` on Windows. The mod jar is written to `build/libs/`.

## Use

Put the jar in the Minecraft `mods` folder. Press **J** to start or stop fishing. Use `/riptide` to open the settings screen. Settings are saved to `config/riptide.json`.

GitHub Actions also builds the jar on pushes and manual workflow runs; download it from the workflow run's artifacts.

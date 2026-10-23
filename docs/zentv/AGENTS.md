# ZenTV: what changes for agents

ZenTV is a fork of [cleanyourtv](https://github.com/Gathin23/cleanyourtv). The root [AGENTS.md](../../AGENTS.md)
is upstream's guide and still applies here. This file lists only what ZenTV changes; where they disagree,
this file wins.

## What ZenTV adds

Opt-in launcher features that upstream leaves out because they use the network or a background service. Each one is off until
`launcher/app/src/main/res/values/zen_config.xml` or a theme's `config.json` turns it on. The keys are in
[CONFIG.md](CONFIG.md).

| Feature | Code | Network |
|---|---|---|
| Weather line under the header | `ZenWeather`, `ZenWeatherIcon`, `ZenPlace`, layouts `zen_weather_*` | `api.open-meteo.com`, every 30 minutes while Home is on screen; `geocoding-api.open-meteo.com` once per `weather_city` |
| Bing's picture of the day as the wallpaper | `ZenDailyPhoto` | `www.bing.com`, once a day while Home is on screen |
| An app opened at power-on (boot and wake from standby), after the network is up | `ZenAutostart`, `ZenHoldActivity`, `ZenKeepAliveService` | None (waits for it) |

`ZenTv` owns them all: lifecycle, downloads (`ZenNet`, HTTPS only, size-capped) and views. `ZenConfig` reads the
keys.

## Rules that differ from upstream

- **Network:** the launcher has the `INTERNET` permission, for the features above only. A new network feature
  is off by default, says in `zen_config.xml` which host it calls and how often, downloads only while Home is
  on screen, and never sends identifiers, accounts or analytics.
- **Background service:** `ZenKeepAliveService` is a foreground service, which upstream does not allow. It runs
  only while `zen_autostart_app` is set. No other background work: no polling while another app is on screen.
- **APK gate:** 524,288 B (512 KB) instead of upstream's 491,520 B, in `.github/workflows/ci.yml` and the root
  AGENTS.md. Keep ZenTV's share of it small.
- **Everything else is upstream's:** `minSdk` 26, no new launcher dependencies, disable never uninstall, the
  Home guard, no identifiers in commits, real-TV checks with screenshots.

## Where ZenTV code goes

Merges from upstream must stay easy, so ZenTV touches upstream files as little as possible:

- New code goes in new files: Java classes named `Zen*`, resources named `zen_*` (`values/zen_config.xml`,
  `values/zen_strings.xml`, `values-es/zen_strings.xml`, `layout/zen_*.xml`).
- `MainActivity` gets only calls into `ZenTv`: the constructor in `onCreate`, then `resume()`, `pause()` and
  `close()`. Add a new feature inside `ZenTv`, not as more lines in `MainActivity`.
- A line that must change in an upstream file carries a `ZenTV:` comment, so a merge conflict explains itself.
- Strings: English in `values/zen_strings.xml`, Spanish in `values-es/zen_strings.xml`. Other languages fall
  back to English.

## Keeping up with upstream

```bash
git fetch upstream
git merge upstream/main          # on a branch, then open a PR into ZenTV's main
```

Resolve conflicts in favour of upstream's code plus the `ZenTV:` lines. Then run the checks below and try
Home on a real TV.

## Checks

The root AGENTS.md checks apply, with the 512 KB gate. In short:

```bash
python3 -m unittest discover -s tests -v
cd launcher && ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
stat -f %z app/build/outputs/apk/release/app-release.apk   # macOS; Linux: stat -c %s
```

ZenTV's JVM tests are `Zen*Test.java` in `launcher/app/src/test/`.

## Pull requests

- `main` is protected: every change, an upstream merge included, goes through a pull request whose two CI checks
  (`Launcher build and unit tests`, `Lists, tools and docs`) pass. No direct pushes, force pushes or deletion,
  for admins too. GitHub's "Sync fork" button pushes to `main`, so it fails: merge upstream on a branch instead.
- Into ZenTV (`hernanzini/zentv`): any change that follows this file.
- Into upstream (`Gathin23/cleanyourtv`): only what fits upstream's own rules, on a branch made from
  `upstream/main`, never with `Zen*` code, `zen_*` resources or the `INTERNET` permission.

## Personal setups

A person's own TV settings go in `mytvs/<tv>/` (ignored by git): their `config.json` and debloat list. Push
one with `TV_HOST=<tv ip> tools/theme.sh mytvs/<tv>`. Never commit IPs, Wi-Fi names, a person's coordinates or
the apps a person uses that are not public examples. A city name (`weather_city`) is fine in examples.

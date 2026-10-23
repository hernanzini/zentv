# ZenTV

**A calmer Android TV, set up by you or by your AI agent.**

ZenTV is a fork of [cleanyourtv](https://github.com/Gathin23/cleanyourtv) by Gathin and its contributors: the
same debloat lists for hundreds of TVs, the same tools and the same small launcher, plus opt-in features that the
upstream launcher leaves out on purpose because they use the network or a background service.

Everything upstream documents still applies: supported TVs, the prompt, the tools and the launcher's settings are
in upstream's [README](../README.md).

## What ZenTV adds

| Feature | Setting | Network |
|---|---|---|
| **Weather** under the clock: now, today's high, low and chance of rain, and the coming hours, for a city by name | `weather`, `weather_city` | [Open-Meteo](https://open-meteo.com), every 30 minutes while Home is on screen |
| **Picture of the day** from Bing as the wallpaper, darkened for the text, with its caption | `daily_wallpaper` | `www.bing.com`, once a day while Home is on screen |
| **Your live-TV app at power-on**, such as Sensa: after a boot and after standby, once the network is up, with an optional deep link | `autostart_app` | None |

Every feature is off until you turn it on. No accounts, no analytics, no ads. Details and privacy notes:
[docs/zentv/CONFIG.md](../docs/zentv/CONFIG.md).

## Set it up with an AI agent

Clone the repository, open it in Claude Code, Codex, Gemini CLI or Cursor, and ask in your own words:

> Clean up my TV at 192.168.1.50, install the ZenTV launcher, and turn on the weather for my city and the
> picture of the day.

The agent follows [PROMPT.md](../PROMPT.md) for the safe part (backups first, disable never uninstall, nothing
the TV needs) and [docs/zentv/AGENTS.md](../docs/zentv/AGENTS.md) for ZenTV's features. It asks before touching
anything your family uses.

## Set it up by hand

1. Enable network debugging on the TV and build the launcher, as upstream's README explains. You need JDK 17 and
   the Android SDK platform 34.
2. Install it: `TV_HOST=<tv ip> launcher/build-and-install.sh`
3. Create a folder with a `config.json`:

   ```json
   { "weather": true, "weather_city": "Resistencia, Chaco, Argentina", "daily_wallpaper": true,
     "autostart_app": "ar.com.sensa.player",
     "autostart_uri": ["minerva://content/livetv", "minerva://keycode/dpad_center"] }
   ```

4. Push it: `TV_HOST=<tv ip> tools/theme.sh <folder>`

Keep your own TVs' folders in `mytvs/`: git ignores it.

## How ZenTV stays current

ZenTV merges upstream's `main` regularly. Its own code lives in files named `Zen*` and `zen_*`, and upstream
files carry only a few lines marked `ZenTV:`, so each merge stays small. Fixes that fit upstream's rules go
back upstream as pull requests.

## License

MIT, like upstream: Copyright (c) 2026 Gathin for cleanyourtv, and (c) 2026 Hernán Zini for ZenTV's changes. The
Outfit typeface is under the SIL Open Font License 1.1 ([launcher/FONT-LICENSE-OFL.txt](../launcher/FONT-LICENSE-OFL.txt)).
Full text: [LICENSE](../LICENSE).

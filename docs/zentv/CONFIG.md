# ZenTV settings

ZenTV's keys work like upstream's ([themes/README.md](../../themes/README.md)): a default in
`launcher/app/src/main/res/values/zen_config.xml` (needs a rebuild), or the same key without the `zen_` prefix in
a theme's `config.json` (no rebuild: `TV_HOST=<tv ip> tools/theme.sh <folder>`). Every key is optional and off by
default; a key of the wrong type keeps the default.

| `config.json` key | `zen_config.xml` | Type | What it does |
|---|---|---|---|
| `weather` | `zen_weather` | bool | The weather line under the header |
| `weather_city` | `zen_weather_city` | string | Where the weather is for, as a place name: `"Resistencia, Chaco, Argentina"`. The parts after the first narrow it down to a province, state or country |
| `weather_latitude`, `weather_longitude` | `zen_weather_latitude`, `zen_weather_longitude` | number | Where the weather is for, in decimal degrees. They win over `weather_city`. With neither, upstream's `latitude` and `longitude` from `config.xml` (the ones the sky uses); with none, no weather line |
| `daily_wallpaper` | `zen_daily_wallpaper` | bool | Bing's picture of the day behind Home |
| `autostart_app`, `autostart_uri` | `zen_autostart_app`, `zen_autostart_uri` | string | The app opened when the TV boots or wakes up onto Home, and an optional deep link sent to it instead of its launch intent |

## Example

```json
{
  "weather": true,
  "weather_city": "Resistencia, Chaco, Argentina",
  "daily_wallpaper": true,
  "autostart_app": "ar.com.sensa.player"
}
```

`ar.com.sensa.player` is Sensa, a live-TV app in Argentina. For another app, `tools/tv.py 'pm list packages'` lists
the package names, and the app's maker documents its deep links, if any.

## Weather line

One line under the clock and the date: an icon and the temperature now, the sky in words, today's high and
low, the chance of rain when it is 10% or more, and the next four hours that fall on 0, 3, 6 ... 21 h, each with
its icon and temperature. Hours follow the TV's 12 or 24-hour setting.

- Source: [Open-Meteo](https://open-meteo.com), no account or key. The request carries the coordinates and
  nothing else.
- With `weather_city`, the TV asks `geocoding-api.open-meteo.com` once for the place's coordinates, keeps them,
  and asks again only when the name changes. Use coordinates instead to send no place name at all.
- Refreshed every 30 minutes while Home is on screen, and after the network comes back. Nothing is fetched
  while another app is on screen.
- The last answer is kept on the TV and shown at once after a restart, for up to 6 hours.
- Degrees Celsius.

## Daily wallpaper

Bing's picture of the day, cropped to the screen, darkened at the top and the bottom so the clock and the tiles
stay readable, with its caption in the top-right corner.

- Source: `www.bing.com`, once a day while Home is on screen, retried every hour until the new picture is out.
  The request carries a market code from the TV's language (`es-ES` for Spanish, `en-US` when there is none).
- Bing offers these pictures for use as wallpapers only. ZenTV downloads them on each TV for that use and never
  ships or shares them. Some days' pictures are not offered as wallpapers: then the newest one of the last eight
  days that is shows instead.
- It covers a theme's own wallpaper. Leave the theme without `wallpaper.jpg` or `wallpapers/` to save memory.
- Without a connection the last picture stays. Before the first one arrives, the theme's look shows.

## App at power-on

For a TV used mostly for one streaming live-TV app (Sensa, an IPTV player): the TV comes on straight
into it, after a boot and after standby. Upstream's `boot_to_live_tv` covers only the boot, opens the Live TV
app at once and sends no deep link.

- It waits for a validated network first, up to 20 s: Ethernet and Wi-Fi come back a few seconds after the
  screen, and a streaming app started offline shows an error. While the TV is in standby, a see-through screen
  holds the app until the network is back and shows "Connecting..." if that takes a while.
- `autostart_uri` is sent on a fresh boot only. After standby the app is brought back where it was.
- Sensa: turn on its own option "Sintoniza En Vivo cuando finaliza la reproducción" (profile, "Mi perfil", device
  settings; it asks for the account PIN) and leave `autostart_uri` empty: Sensa then plays the last channel, or the
  first one, as soon as it starts. Without that option, `minerva://content/livetv` opens its guide on the last
  channel, and OK twice plays it.
- Links only, never keys. A key sent by deep link before the app has loaded lands wherever the app happens to be:
  OK for Sensa's guide, sent right after a boot, opened a live sports event from Sensa's home screen instead.
- A wake-up into another app or into an HDMI input switched on through HDMI-CEC is left alone, and the remote's
  Home button still shows Home.
- To notice standby while the app is on screen, Home keeps a foreground service running. Some firmware (TCL)
  stops background apps otherwise. It runs only while `autostart_app` is set.
- Use `autostart_app` or upstream's `boot_to_live_tv`, not both.

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
| `autostart_steps` | `zen_autostart_steps` | list of strings | After a boot, `"view:link"` entries: each link goes to that app once its view with that id has focus. Needs an accessibility service ([below](#steps-after-a-boot)) |

## Example

```json
{
  "weather": true,
  "weather_city": "Resistencia, Chaco, Argentina",
  "daily_wallpaper": true,
  "autostart_app": "ar.com.sensa.player",
  "autostart_uri": "minerva://content/livetv",
  "autostart_steps": ["browse_headers:minerva://content/livetv", "grid:minerva://keycode/media_play"]
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

- It waits for a validated network first, because a streaming app started offline shows an error. After
  standby, Ethernet and Wi-Fi come back a few seconds after the screen: the wait is up to 20 s, and a see-through
  screen holds the app until the network is back and shows "Connecting..." if that takes a while. After a boot,
  Home waits up to 5 minutes: when the power comes back after a cut, the router starts again too, and Wi-Fi can
  take minutes.
- `autostart_uri` is sent on a fresh boot only. After standby the app is brought back where it was.
- Sensa: set `autostart_uri` to `minerva://content/livetv`. After a boot, Sensa opens its guide on the last
  channel, sometimes with its side menu open, and OK twice plays the channel; `autostart_steps` plays it
  instead ([below](#steps-after-a-boot)). Without the link Sensa opens on its home screen. Sensa never plays by
  itself at start: its build sets no default channel, and its option "Sintoniza En Vivo cuando finaliza la
  reproducción" acts only when a film or a recording ends. After standby, Sensa comes back on the channel that
  was on.
- Started offline, Sensa shows "no connection" after 30 s and, on retry, asks for the profile. Its option "No
  mostrar esta pantalla automáticamente" covers only coming back to Sensa.
- Links only, never blind keys. A key sent by deep link before the app has loaded lands wherever the app happens
  to be: OK for Sensa's guide, sent right after a boot, opened a live sports event from Sensa's home screen
  instead. `autostart_steps` sends a key's link only once the screen it is meant for has focus.
- A wake-up into another app or into an HDMI input switched on through HDMI-CEC is left alone, and the remote's
  Home button still shows Home.
- To notice standby while the app is on screen, Home keeps a foreground service running. Some firmware (TCL)
  stops background apps otherwise. It runs only while `autostart_app` is set.
- Use `autostart_app` or upstream's `boot_to_live_tv`, not both.

### Steps after a boot

`autostart_steps` lists `"view:link"` entries. After a boot, once the `autostart_app` view with that id, or a view
inside it, has focus, ZenTV sends the link to the app. The last entry ends it. For Sensa:

- `grid:minerva://keycode/media_play`: `grid` is the guide. In the guide, the first OK only expands the program,
  and Play plays the marked channel, the last one watched.
- `browse_headers:minerva://content/livetv`, listed first: after a boot Sensa sometimes shows its side menu,
  over the guide or over its home screen. The guide link again closes the menu and gives the guide focus.

To find a view's id, open the app on the screen you want and run
`tools/tv.py 'uiautomator dump /data/local/tmp/ui.xml && cat /data/local/tmp/ui.xml'`: the focused node has
`focused="true"`, and its `resource-id` ends with the id.

Only an accessibility service can see another app's views, so the steps need ZenTV's own, "ZenTV: app at
power-on". Turn it on once per TV, after the launcher is installed:

```bash
tools/tv.py 'settings put secure enabled_accessibility_services com.gathin.tvhome/com.gathin.tvhome.ZenAutostartSteps'
tools/tv.py 'settings put secure accessibility_enabled 1'
```

That command replaces the list of accessibility services: when `settings get secure
enabled_accessibility_services` shows others, add ZenTV's to that list with a `:` between entries.

- The service gets no events until a boot opens the app, then only the app's focus changes, for 3 minutes. It
  reads the id of the focused view and of its parents, nothing else.
- It acts after a boot only, never after standby. A view must keep focus for 1.5 s, links go at least 5 s
  apart, and at most 6 per boot. The last entry's link goes again once when its view still has focus 5 s later.
- It does not turn on touch exploration, so apps do not take it as TalkBack. Sensa's guide ignores OK when
  TalkBack is on.
- If the app changes its screens in an update, the ids no longer match and nothing is sent: the app stays where
  `autostart_uri` left it.

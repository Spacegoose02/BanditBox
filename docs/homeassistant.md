# Home Assistant

Each paired TV tells the BanditBox server what it is doing, and the server passes that on to Home Assistant. Use it for automations such as dimming the lights when a movie starts, or turning them back up when it's paused.

Set it up in the web UI under **Integrations → Home Assistant**. There are two ways in, and you can use both.

## MQTT (recommended)

Enter your broker, for example `mqtt://192.168.1.10:1883` (the Mosquitto add-on), with a username and password if it needs one. Press **Test**, then **Save**.

With MQTT discovery (on by default in Home Assistant), every TV shows up as a device named after the TV, with three entities:

| Entity | State | Notes |
| --- | --- | --- |
| `sensor.banditbox_<tv>_app` | The app in front, such as `Plex` | Attributes: `app` (package), `app_label`, `playing`, `screen_on` |
| `binary_sensor.banditbox_<tv>_playing` | `on` while sound plays | Video apps play on the same stream as music |
| `binary_sensor.banditbox_<tv>_screen` | `on` while the screen is on | |

Topics, with the default prefixes:

- `banditbox/status`: `online`, or `offline` when the server goes away (last will).
- `banditbox/<tv id>/state`: `{"app":"com.plexapp.android","app_label":"Plex","playing":true,"screen_on":true}`, retained.
- `homeassistant/<sensor|binary_sensor>/banditbox_<tv id>/<app|playing|screen>/config`: discovery, retained.

Dim the lights when a movie starts in Plex:

```yaml
alias: Movie lights
triggers:
  - trigger: state
    entity_id: binary_sensor.banditbox_living_room_playing
    to: "on"
conditions:
  - condition: state
    entity_id: sensor.banditbox_living_room_app
    state: Plex
actions:
  - action: light.turn_on
    target: { entity_id: light.living_room }
    data: { brightness_pct: 10, transition: 3 }
```

## Webhook

Enter a URL, for example a Home Assistant webhook trigger (`http://homeassistant.local:8123/api/webhook/banditbox`). Each change is a POST:

```json
{
  "event": "playback_started",
  "tv": { "id": "d_abc123", "name": "Living room" },
  "state": { "app": "com.plexapp.android", "app_label": "Plex", "playing": true, "screen_on": true }
}
```

Events: `app_changed`, `playback_started`, `playback_stopped`, `screen_on`, `screen_off`. **Test** sends `"event": "test"`.

## What the TV knows

- **The app in front**: exactly while the BanditBox service is on (Home button setup). With it off, the TV can only tell BanditBox's own screen from "another app", and `app` is empty for the latter.
- **Playing**: whether sound is playing, checked every 5 seconds while the screen is on. No permission is needed, and nothing about what is playing is read.
- **Screen**: Android's screen on and off.

A TV reports only while it is paired with the server, and only when something changed. The server keeps the last report of each TV in memory and re-publishes them when it reconnects to the broker.

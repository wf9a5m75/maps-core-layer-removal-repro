# maps-core: removing a tiled layer leaves its tile loads running

A minimal Android app — two files, ~250 lines — that uses **only** `io.openmobilemaps:mapscore:4.0.0`.
It shows that `MapInterface.removeLayer()` does not stop the removed layer's tile
loads, and that those orphaned loads then starve the layers that are still on the map.

No network and no API keys: the tiles come from an HTTP server inside the app,
which also counts what arrives.

## Run it

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # or any JDK 17
./gradlew :app:installDebug
adb shell am start -n com.example.ommstall/.MainActivity
adb logcat -s OmmStall
```

The app does three things on a timer:

| every | what |
|---|---|
| 300 ms | moves the camera onto ground it has not visited, so a working layer must keep loading tiles |
| 4 s | replaces the overlay layer: `removeLayer(old)` then `insertLayerAt(new, 1)`, with a new `?v=` in the tile URL |
| 3 s | reports how many tiles each layer fetched |

The bottom layer is created once and never touched again. Tiles on the `/slow`
route take 800 ms to answer, which stands in for tiles an app rasterises itself.

## What you should see

Within about fifteen seconds:

```
WINDOW moves=+10 fixedLayer=+43 replacedLayer=+42 ofWhichAlreadyRemoved=+10 | ...
WINDOW moves=+9  fixedLayer=+10 replacedLayer=+30 ofWhichAlreadyRemoved=+29 | ...
WINDOW moves=+10 fixedLayer=+1  replacedLayer=+29 ofWhichAlreadyRemoved=+28 | ...
WINDOW moves=+10 fixedLayer=+0  replacedLayer=+31 ofWhichAlreadyRemoved=+30 | ...
*** the layer that was never touched has been starved for 9s: it fetched 1 tiles
    while the replaced layer fetched 30, 28 of them for layers already removed ***
```

`ofWhichAlreadyRemoved` counts tiles fetched for a `?v=` that is no longer the
live layer's — i.e. work done for layers that are not on the map any more. It
grows to about 95% of all tile traffic, and the layer that is still on the map
gets nothing.

Requests for a removed layer's tiles keep reaching the server for **1.6 s to 9 s**
after `removeLayer` returns, 12–46 of them each time — they are never cancelled.

## Switches, for narrowing it down

```bash
adb shell am start -n com.example.ommstall/.MainActivity --ez swap false
adb shell am start -n com.example.ommstall/.MainActivity --ez shared_loader false
adb shell am start -n com.example.ommstall/.MainActivity --ez pause_before_remove true
adb shell am start -n com.example.ommstall/.MainActivity --ez stop_loading_before_remove true
adb shell am start -n com.example.ommstall/.MainActivity --ez big_pool true
adb shell am start -n com.example.ommstall/.MainActivity --ei slow_ms 400
```

Measured on a Sony Xperia Ace (SO-02L, Android 10, arm64-v8a), tiles fetched by
the untouched layer per 3 s once the run settles:

| run | untouched layer | note |
|---|---|---|
| defaults | **0–2** | the failure |
| `--ez swap false` | 108–119 | never replacing the overlay is fine |
| `--ez shared_loader false` | 147–155 | one `DataLoader` per layer works around it |
| `--ez pause_before_remove true` | 0–2 | `pause()` before `removeLayer` does not help |
| `--ez stop_loading_before_remove true` | 0–6 | `setTileLoadingPaused(true)` does not help either |
| `--ez big_pool true` | 137–154 | raising OkHttp's per-host limit to 64 also works around it |

The last two rows settle the mechanism. Both layers share one `DataLoader`, and
OkHttp allows five concurrent requests per host by default; every tile here is on
127.0.0.1. Loads belonging to removed layers hold those five slots for their full
800 ms, so the live layer never gets one. Raising the limit to 64 restores it
completely — the orphaned loads still happen, they just no longer block anyone.

This also explains why the symptom looks selective in a real app: only layers
sharing a host with the orphaned loads go quiet, while layers on other hosts keep
loading normally.

## How we ran into it

Our own SDK puts its markers into raster tiles served by a local HTTP server, and
replaces that layer whenever the marker data changes. On a slow phone the overlay
stopped updating while the base map kept loading normally — the base map is on a
different host, so it never competed with the orphaned loads. This project is
that situation with everything of ours removed.

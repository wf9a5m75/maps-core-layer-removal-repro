# removeLayer() leaves a tiled layer's loads running, and they starve the layers still on the map

**Version:** `io.openmobilemaps:mapscore:4.0.0` (Android)
**Device:** Sony Xperia Ace (SO-02L), Android 10, arm64-v8a
**Build:** AGP 9.2.1, Kotlin 2.4.10, JDK 17, `compileSdk 37` / `minSdk 28`

## Summary

Requests for a `Tiled2dMapRasterLayer`'s tiles keep arriving at the tile server
for several seconds after `MapInterface.removeLayer(layer)` returns: the loads
are never cancelled, neither by `removeLayer` nor by anything we could call
first. If another layer shares the
`LoaderInterface` — the ordinary case, since apps pass one `DataLoader` to every
layer — those orphaned loads take the loader's capacity and the layer that is
still on the map stops loading tiles almost entirely.

An app that replaces a layer periodically (we do it when the data behind our
tiles changes) therefore ends up with a map whose other layers no longer follow
the camera.

## Reproduction

A minimal app is attached: two source files, ~250 lines, `mapscore` as its only
map dependency. No network and no API key — tiles are served by an HTTP server
inside the app, which also counts what arrives.

```bash
./gradlew :app:installDebug
adb shell am start -n com.example.ommstall/.MainActivity
adb logcat -s OmmStall
```

The app, on timers:

- every **300 ms** moves the camera onto ground it has not visited (so a working
  layer must keep loading tiles),
- every **4 s** replaces the overlay layer — `removeLayer(old)`, then
  `insertLayerAt(new, 1)` built from `DefaultTiled2dMapLayerConfigs.webMercator`
  with a new `?v=` in the URL,
- every **3 s** reports how many tiles each layer fetched.

The bottom layer is created once at startup and never touched again. Tiles on the
overlay's route take 800 ms to answer, standing in for tiles an app rasterises
itself.

## Expected

The layer that is never touched keeps loading tiles as the camera moves, and a
layer removed from the map stops loading.

## Actual

```
WINDOW moves=+10 fixedLayer=+43 replacedLayer=+42 ofWhichAlreadyRemoved=+10 | totals fixedLayer=214 replacedLayer=189 alreadyRemoved=49
WINDOW moves=+9  fixedLayer=+10 replacedLayer=+30 ofWhichAlreadyRemoved=+29 | totals fixedLayer=224 replacedLayer=219 alreadyRemoved=78
WINDOW moves=+10 fixedLayer=+1  replacedLayer=+29 ofWhichAlreadyRemoved=+28 | totals fixedLayer=225 replacedLayer=248 alreadyRemoved=106
WINDOW moves=+10 fixedLayer=+1  replacedLayer=+30 ofWhichAlreadyRemoved=+28 | totals fixedLayer=228 replacedLayer=307 alreadyRemoved=163
*** the layer that was never touched has been starved for 9s: it fetched 1 tiles while the
    replaced layer fetched 30, 28 of them for layers already removed from the map ***
WINDOW moves=+10 fixedLayer=+0  replacedLayer=+31 ofWhichAlreadyRemoved=+30 | totals fixedLayer=229 replacedLayer=366 alreadyRemoved=220
WINDOW moves=+9  fixedLayer=+0  replacedLayer=+31 ofWhichAlreadyRemoved=+31 | totals fixedLayer=232 replacedLayer=512 alreadyRemoved=362
```

`ofWhichAlreadyRemoved` counts requests carrying a `?v=` that is no longer the
live layer's, so they can only come from layers already removed from the map.
They grow to ~95% of all tile traffic, and the layer that is still on the map
fetches nothing.

Matching each request against the removal it followed:

```
layer v=1 removed at 01:13:12.625, 24 more of its tiles were still fetched, last 1.9s later
layer v=2 removed at 01:13:16.637, 36 more of its tiles were still fetched, last 3.5s later
layer v=3 removed at 01:13:20.640, 46 more of its tiles were still fetched, last 4.6s later
layer v=5 removed at 01:13:28.646, 46 more of its tiles were still fetched, last 8.4s later
```

Cancellation counts point the same way. With the overlay left alone, the live
layer cancels loads as the camera moves on — the server sees 962 connections
closed mid-response in 45 s. With the overlay being replaced, that drops to 125,
while requests for removed layers pile up.

## Narrowing it down

Tiles fetched by the untouched layer per 3 s window, once the run settles:

| run | untouched layer | |
|---|---|---|
| defaults | **0–2** | the failure |
| `--ez swap false` (never replace the overlay) | 108–119 | fine |
| `--ez shared_loader false` (a `DataLoader` per layer) | 147–155 | fine |
| `--ez pause_before_remove true` (`pause()` then `removeLayer`) | 0–2 | does not help |
| `--ez stop_loading_before_remove true` (`setTileLoadingPaused(true)` then `removeLayer`) | 0–6 | does not help |
| `--ez big_pool true` (OkHttp `maxRequestsPerHost = 64`) | 137–154 | fine |

Two things follow. Neither `LayerInterface.pause()` nor
`Tiled2dMapRasterLayerInterface.setTileLoadingPaused(true)` before removal stops
the loads, so we could not find an app-side way to tear the layer down first.
And the damage travels
through the shared loader, by way of OkHttp's default of five concurrent requests
per host: the orphaned loads hold those five slots for their full 800 ms. Either
giving each layer its own `DataLoader` or raising the per-host limit to 64
restores the untouched layer fully, while the orphaned loads carry on in both
cases — they simply stop blocking anyone.

That also explains why the symptom looks selective in a real app: only layers
sharing a host with the orphaned loads go quiet, while layers on other hosts keep
loading normally.

## Questions

1. Should `removeLayer()` (or `LayerInterface.onRemoved()`) cancel the layer's
   pending tile loads, including calling `LoaderInterface.cancel()` for requests
   already handed to the loader?
2. If an app is instead expected to tear a tiled layer down itself before
   removing it, which call is that? Neither `pause()` nor
   `setTileLoadingPaused(true)` did it, and we found nothing else in the public
   interface.

## Where we hit it

Found while testing our own SDK, which draws markers into raster tiles served by
a local HTTP server and replaces that layer when the marker data changes. On a
slower phone the marker layer stopped updating while the base map kept loading
normally — the base map is on a different host, so it never competed with the
orphaned loads. The attached project is that situation with all of our code
removed.

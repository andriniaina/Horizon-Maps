# EV chargers

Summary of commit `5e53dd32c59817c3244dc41f783f021fbaf67b7e`.

## Connector preferences

In **Settings → Fuel & charging → Your electric car**, use **My charger connectors** to select the plugs your car accepts. An empty selection means any connector. With a selection, confirmed matches rank first, chargers without recognized connector data remain as fallbacks, and known mismatches are excluded. This applies to nearby and along-route charger lists, one-tap charging stops, and automatic EV trip suggestions.

## When to stop

By default, automatic selection prefers the shortest detour among chargers with the same compatibility ranking. Turn on **Charge as late as possible** to aim for **15–25% battery on arrival at the charger**. Within the same compatibility ranking, the app picks the furthest candidate in that band. If none is in the band, it picks the closest to it, with ties going to the furthest candidate. The band is a target, not a hard minimum.

## Offline charger data

Under **Settings → Offline maps → Chargers**, download, update, or remove charging points for the selected region. The [country-pack workflow](.github/workflows/build-country-packs.yml) builds `chargers.tsv` from OpenStreetMap and publishes it with SHA-256 checksums. It includes connector types, power, and point counts where mapped.

Downloaded packs replace Overpass for charger lookups and are required for offline search. Online providers still participate: TomTom can supply charger results and live availability where available, and Mapbox can supply results when connector data is not required. Older packs without a charger asset need to be rebuilt and published before their chargers can be downloaded.

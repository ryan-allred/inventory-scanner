# Sealed Pokémon Inventory

A small native Android app for tracking sealed Pokémon card products by UPC.

## Features

- Scan UPC-A, UPC-E, EAN-13, and EAN-8 barcodes with the phone camera.
- Look up a product name from UPCitemdb, stripping common leading Pokémon / TCG branding from scanned product names. Names remain editable, and you can add products manually if lookup has no match.
- Cache product names, image URLs, and confirmed missing results on the device. Repeat scans reuse cached results and pending work instead of making another UPCitemdb request.
- Try up to eight cached product image URLs in order, save the first valid image on the device, and show it beside the inventory item for offline viewing. Image retries do not repeat the product lookup.
- Queue lookups durably, processing up to two barcodes per request within the free API's six-requests-per-minute and 100-requests-per-day limits. Unfinished lookup and image work resumes when the app is reopened.
- Save inventory on the device. Scanning a UPC already in the inventory increments its quantity instead of creating a duplicate.
- Use `+` and `−` to adjust quantities. Tapping `−` at quantity one asks before removing the item.
- Export the inventory as a CSV file through Android's save-file picker.
- Long-press a product to edit its name, remove it, or explicitly retry its product details / image.

## Build

Open this folder in Android Studio, or build from a machine with Java 17, Android SDK Platform 36, and Gradle 9.3.1:

```sh
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat`. Run `./gradlew :app:testDebugUnitTest :app:lintDebug` for the queue/cache recovery tests and Android lint checks.

GitHub Actions is configured in `.github/workflows/android.yml`. It builds and uploads the debug APK on pushes and pull requests to `main` or `master`. CI sets `versionCode` from `GITHUB_RUN_NUMBER`, so each workflow run receives an increasing version code.

## Installable updates from GitHub Actions

Android only accepts an in-place APK update when its application ID and signing certificate match the installed app. The application ID is `com.example.pokemoninventory`; keep it unchanged. The debug build is signed with the fixed development keystore at `app/signing/pokemoninventory-dev.jks`, so every GitHub Actions debug APK from this repository uses the same certificate.

Keep this keystore unchanged and include it in future project uploads. Replacing it changes the signing certificate and prevents in-place updates. Because the keystore is checked into this repository, it is a development key and should not be reused for a publicly distributed production app. No GitHub Secrets are required for these update builds.

## Product-name lookup

Each scan immediately saves a new inventory item or increments its quantity. A new uncached barcode shows “Waiting for lookup” until its name is available. Lookup completion preserves quantity changes and manually edited names, and never restores an item that was removed while its lookup was running. Scanning and managing inventory work offline; queued network work waits for connectivity.

Product metadata and confirmed “no match” responses are cached indefinitely, including after removing and re-adding a product. UPC-A and its zero-prefixed EAN/GTIN equivalents share the same cache and queue entry; camera scans of UPC-E are expanded to UPC-A first. A confirmed missing result shows “Unknown product” unless the name has been edited. Network errors, malformed responses, and rate limits are retried with exponential backoff rather than cached as missing products.

The public UPCitemdb trial endpoint supports [up to two barcodes per request, six lookup requests per minute, and 100 combined requests per day](https://www.upcitemdb.com/wp/docs/main/development/plan/). A single dispatcher processes the oldest eligible jobs in batches of up to two; a lone barcode does not wait for another scan. Request attempts are recorded before sending, including failed attempts and retries. The app enforces rolling 60-second and 24-hour windows, conservatively respecting the daily allowance even if the server resets earlier. Server `Retry-After` and exhausted `X-RateLimit-Reset` headers can extend the wait. Request history and cooldowns survive restarts.

Image work uses only cached image URLs, with at most two downloads running at once and one image job per barcode. Repeat scans during lookup, queued retry, or image loading only increase quantity. Failed image jobs retry with backoff up to five attempts, then show “Image unavailable.” Long-press and select “Retry product details / image” to retry cached image URLs, or refresh product details when no match/image URLs were available. Explicit retry does not duplicate already queued or running work, and obeys the same API limits.

Inventory, metadata, jobs, and request history are stored in Room transactions. Existing SharedPreferences inventory is imported once, preserving quantities, names, and saved images. Image files use atomic writes; cached images remain available when a product is re-added. Interrupted jobs recover on startup and resume while the inventory screen is open. Work is not scheduled to run while the app is closed; active requests can finish while its process is still alive. If the app terminates after a server receives a request but before its response is saved, that lookup may be repeated on recovery; the reserved rate-limit slot is retained.

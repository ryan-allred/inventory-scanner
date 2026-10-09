# Sealed Pokémon Inventory

A small native Android app for tracking sealed Pokémon card products by UPC.

## Features

- Scan UPC-A, UPC-E, EAN-13, and EAN-8 barcodes with the phone camera.
- Look up a product name from UPCitemdb, stripping common leading Pokémon / TCG branding from scanned product names. Names remain editable, and you can add products manually if lookup has no match.
- Try up to eight product image URLs from UPCitemdb in order, save the first valid image on the device, and show it beside the inventory item for offline viewing. If no image source works, the product is still added; scanning it again retries the image lookup.
- Save inventory on the device. Scanning a UPC already in the inventory increments its quantity instead of creating a duplicate.
- Use `+` and `−` to adjust quantities. Tapping `−` at quantity one asks before removing the item.
- Export the inventory as a CSV file through Android's save-file picker.
- Long-press a product to edit its name or remove it.

## Build

Open this folder in Android Studio, or build from a machine with Java 17, Android SDK Platform 36, and Gradle 9.3.1:

```sh
gradle :app:assembleDebug
```

GitHub Actions is configured in `.github/workflows/android.yml`. It builds and uploads the debug APK on pushes and pull requests to `main` or `master`. CI sets `versionCode` from `GITHUB_RUN_NUMBER`, so each workflow run receives an increasing version code.

## Installable updates from GitHub Actions

Android only accepts an in-place APK update when its application ID and signing certificate match the installed app. The application ID is `com.example.pokemoninventory`; keep it unchanged. The debug build is signed with the fixed development keystore at `app/signing/pokemoninventory-dev.jks`, so every GitHub Actions debug APK from this repository uses the same certificate.

Keep this keystore unchanged and include it in future project uploads. Replacing it changes the signing certificate and prevents in-place updates. Because the keystore is checked into this repository, it is a development key and should not be reused for a publicly distributed production app. No GitHub Secrets are required for these update builds.

## Product-name lookup

The app calls the public UPCitemdb trial lookup endpoint. Its catalog may not contain every Pokémon product, and its free endpoint has request limits, so a missing result is added as “Unknown product”; long-press it to enter or correct the name. Lookup requires an internet connection; scanning and managing saved inventory work offline.

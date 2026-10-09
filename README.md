# Sealed Pokémon Inventory

A small native Android app for tracking sealed Pokémon card products by UPC.

## Features

- Scan UPC-A, UPC-E, EAN-13, and EAN-8 barcodes with the phone camera.
- Look up a suggested product name from UPCitemdb. The name remains editable, and you can add products manually if lookup has no match.
- Save inventory on the device. Scanning a UPC already in the inventory increments its quantity instead of creating a duplicate.
- Use `+` and `−` to adjust quantities. Tapping `−` at quantity one asks before removing the item.
- Export the inventory as a CSV file through Android's save-file picker.
- Long-press a product to edit its name or remove it.

## Build

Open this folder in Android Studio, or build from a machine with Java 17, Android SDK Platform 35, and Gradle 8.9:

```sh
gradle :app:assembleDebug
```

GitHub Actions is configured in `.github/workflows/android.yml`. It builds the debug APK on pushes and pull requests to `main` or `master`, and makes the APK available as a workflow artifact.

## Product-name lookup

The app calls the public UPCitemdb trial lookup endpoint. Its catalog may not contain every Pokémon product, and its free endpoint has request limits, so a missing result is handled by prompting you to enter or correct the product name. Lookup requires an internet connection; scanning and managing saved inventory work offline.

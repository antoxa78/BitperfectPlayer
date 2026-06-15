#!/bin/bash
# Script to rename the APK to the release name
VERSION="2.4.2"
APK_PATH="app/build/outputs/apk/release/app-release.apk"
DEST_PATH="app/build/outputs/apk/release/Bitperfect-Player$VERSION.release.apk"

if [ -f "$APK_PATH" ]; then
    cp "$APK_PATH" "$DEST_PATH"
    echo "Success: Renamed APK to $DEST_PATH"
else
    echo "Error: Release APK not found at $APK_PATH"
    exit 1
fi

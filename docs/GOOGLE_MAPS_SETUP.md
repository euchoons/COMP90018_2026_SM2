# Observe location map

The Observe page displays a Google map under Location context. The marker, accuracy circle and
camera use `FloraGuideUiState.location`, the same coordinates used for nearby species context.
`LocationTracker` requests GPS/network updates every 2.5 seconds with a 3 metre minimum distance;
actual delivery depends on the device, provider and permission accuracy. Each delivered coordinate
updates the marker and camera while preserving zoom. Map panning is disabled so vertical swipes
can scroll the Observe page. Zoom controls and pinch zoom remain available.

Demo coordinates are explicitly labelled and have no accuracy circle. The map does not start
another location subscription or enable the SDK's separate My Location layer. Approximate location
permission still works; its accuracy circle can be much larger. Missing key configuration shows
an unavailable message while the rest of the observation flow remains usable.

## API key setup

1. Create or select a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Link a billing account and enable **Maps SDK for Android** for that project.
3. In **APIs & Services → Credentials**, create an API key.
4. Under its application restrictions choose **Android apps**, then add package
   `au.edu.unimelb.floraguide` and the signing certificate SHA-1 for your build.
   Obtain the debug SHA-1 from the `debug` variant in this command's output:

   ```powershell
   .\gradlew.bat :app:signingReport
   ```

   Use Android Studio's configured Gradle JDK if Java is not on your shell's PATH.
   Each developer's debug keystore may have a different SHA-1. Add the appropriate release
   or Play App Signing SHA-1 when distributing a release build.
5. Under API restrictions, allow **Maps SDK for Android** only.
6. Add this line to the project-root `local.properties` (already ignored by Git):

   ```properties
   MAPS_API_KEY=your_actual_key
   ```

   Alternatively, set the `MAPS_API_KEY` environment variable for the build process.
   The local property takes precedence. Do not commit the key. It is necessarily included in
   the installed app's manifest, so package/certificate and API restrictions matter.
7. Sync Gradle, rebuild and reinstall. Configuration is read at build time.

See Google's [Maps SDK setup guide](https://developers.google.com/maps/documentation/android-sdk/get-api-key).
Maps Compose 6.12.0 is pinned to stay compatible with this project's Kotlin 2.2 toolchain.

## Device verification

- Use an Android device or emulator with Google Play services and internet access.
- Open Observe and allow precise location. Verify that the marker and camera show the device's
  location. Walk outdoors or change the emulator's location twice; verify that both move with
  each fix and the accuracy circle updates. Zoom in and verify updates preserve that zoom.
- Choose Demo: verify the campus marker is labelled as a sample and has no accuracy circle.
  Choose Use live: verify a device fix replaces it.
- Deny location, allow approximate location, and revoke permission in Settings. Confirm the
  screen remains usable and demo coordinates are not labelled as the device's live location.
- Rotate the device and leave/reopen Observe; verify map recreation and continued updates.
- Build without a key: verify the unavailable message instead of a map initialization crash.
- If tiles are blank with a key, check Logcat for Maps authorization errors, SDK enablement,
  billing, package name, signing SHA-1 and connectivity. A successful build alone does not
  validate Google Cloud authorization or live GPS behavior.

# ANRO-BEAM Android app

Android source for ANRO-BEAM, currently version **0.1.8.9**.

## Build

Open this folder in Android Studio and let it sync the Gradle project. Install Android SDK Platform 36 and the Android SDK Build-Tools through SDK Manager if prompted, then build the `app` module.

The source includes the animated first-run tutorial and an in-app update checker. Updating an installed APK still requires Android's confirmation screen. The update checker uses the JSON manifest URL configured in `ShareActivity.kt`; publish the matching manifest and APK before using the updater in production.

Do not commit signing keys, `local.properties`, generated build output, or release APKs to this source repository.

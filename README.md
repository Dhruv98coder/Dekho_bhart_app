# Dekho Bharat Android App

This is the Android Studio client for the existing Dekho Bharat Django application.

## Architecture

Android (Kotlin + WebView) -> existing Django REST/HTML backend -> database/AI/map/weather services.

The app intentionally keeps the existing backend as the single source of truth, so GoPlan, Intelligent Map, Shristi, Metro, Native Language and Trip History keep their existing logic.

## Backend URL

Release/debug builds currently point to:

`https://dekho-bharat.onrender.com/`

If your backend moves, change `BuildConfig.BASE_URL` in `app/build.gradle.kts`.

For a local Django server:
- Android emulator: use `http://10.0.2.2:8000/` and enable cleartext traffic for debug.
- Physical phone: use your PC's LAN IP, e.g. `http://192.168.1.10:8000/`, with Django `ALLOWED_HOSTS` and firewall configured.

## Native Android support added

- Runtime microphone permission.
- Runtime location permission and WebView geolocation callbacks.
- Native Android SpeechRecognizer fallback for the Native Language module.
- Native Android TextToSpeech fallback for translation playback and map voice guidance.
- Back button navigation inside the WebView.
- External links, `tel:` links and map links open through Android intents.
- DOM storage, JavaScript and media playback enabled.
- Swipe/scroll and responsive mobile layout are handled by WebView.

## Build

Open this folder in Android Studio, let Gradle sync, then run on an emulator/device.

The provided environment does not include the Android SDK, so an APK cannot be compiled here; the Android Studio project is ready to build on a machine with Android SDK/Gradle available.

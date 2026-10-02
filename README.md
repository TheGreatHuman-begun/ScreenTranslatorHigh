# Screen Translator

Android screen translator prototype with a floating overlay bubble, screen capture, OCR, and translation.

## Build with GitHub Actions

Push the repository to GitHub. The workflow at `.github/workflows/build-apk.yml` builds a debug APK on every push to `main` and can also be started manually from **Actions → Build Android APK → Run workflow**.

The generated artifact is named `ScreenTranslator-debug-apk` and contains `app-debug.apk`.

## Local build

Use Android Studio with JDK 17. The project uses Android Gradle Plugin 8.7.0 and Gradle 8.9.

```bash
gradle :app:assembleDebug
```

## Important

This configuration matches the current source code. The current source uses Google's bundled ML Kit for OCR and translation. It is therefore **not yet the Google-independent version** planned for the later redesign.

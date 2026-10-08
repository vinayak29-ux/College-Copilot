# My Assistant demo setup

The Android app currently calls Backboard directly. The Node.js proxy in this folder is not used by the app.

For a local demo, put the Backboard API key in the ignored `local.properties` file at the Android project root:

```properties
BACKBOARD_API_KEY=your-demo-key
```

Add `BACKBOARD_API_KEY` on its own line. Keep any existing `sdk.dir` line. Then sync Gradle and run the app from Android Studio. Do not commit `local.properties` or share the APK: the key is compiled into the app and can be recovered from it.

The My Assistant screen creates or reopens its Backboard thread and stores the thread ID in private app preferences. Local llama.cpp chats continue to run through the existing local inference implementation.

The `server.js` proxy remains available as a starting point for moving the key off-device later. Before using it, add user authentication and deploy it over HTTPS; do not expose the development proxy publicly without those controls.

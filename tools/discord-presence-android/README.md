# Miyorare Discord Rich Presence prototype

This standalone APK exercises Discord's direct Rich Presence RPC on Android. It uses a sample reading session and leaves the production Reader integration out of the test. The native SDK talks to the signed-in Discord app using a registered Discord Application ID.

## Build

Enable the Social SDK for the Discord application in the [Developer Portal](https://discord.com/developers/applications). Download the [official Discord Social SDK for C++](https://docs.discord.com/developers/discord-social-sdk/getting-started/using-cpp) version 1.10 or later and extract the Android `discord_partner_sdk.aar`. Keep the archive outside the Git checkout.

Run from the repository root with JDK 21, Android SDK 37, an Android NDK supported by the SDK, and CMake 3.22.1 installed.

```sh
./gradlew -p tools/discord-presence-android assembleDebug -PdiscordSocialSdkAar=/absolute/path/discord_partner_sdk.aar
```

The build produces `tools/discord-presence-android/build/outputs/apk/debug/miyorare-discord-presence-debug.apk` for arm64 Android devices. It defaults to the Miyorare Application ID already used by the app. Set `-PdiscordPresenceAppId=YOUR_APPLICATION_ID` to use a separate registered test application.

## Check the card with two accounts

1. Install the APK alongside Miyorare on an arm64 Android phone. Sign in to Discord on that phone and enable activity sharing for the test account.
2. Open **Miyorare Discord Presence** and tap **Tampilkan kartu uji**. Keep that app open. On a second device and account, open the first account's Discord profile.
3. Verify **Komik Uji Miyorare**, **Bab 12**, the Miyorare icon used as a test cover, and a reading timer that counts up. Tap **Baca di sumber** and verify it opens the Miyorare repository used as a test link.
4. Tap **Ganti bab** in the prototype. Expect **Bab 13** and a timer that continues from the original start time.
5. Tap **Hapus kartu uji** and check that the second account no longer sees the test card. Publish once more, then switch away from the prototype and check that the card clears again.

Discord controls card layout and shows activity buttons to other users. The test cover is a public image URL. Private covers and Reader events such as Incognito are outside this standalone smoke test. A successful SDK callback confirms submission. The second account verifies what Discord displays.

# kChat

`kChat` is a Karoo extension prototype that renders a scrollable Twitch chat feed inside a graphical profile page.

## What is implemented

- A Karoo extension service with one graphical data type: `live-chat`
- A full-page `RemoteViews` layout with a scrollable `ListView`
- A setup activity to save the Twitch client ID and optional channel login
- Twitch Device Code Flow login without any redirect URI
- Twitch EventSub over WebSocket for `channel.chat.message`
- A buffered in-memory and cached-on-disk message store
- Karoo view refresh throttling to roughly once per second

## Twitch setup

1. Create a Twitch application in the Twitch developer console.
2. Set the app type so it can use Twitch Device Code Flow as a public client.
3. Open the app on the Karoo device.
4. Paste the Twitch client ID.
5. Optionally set a channel login. If you leave it blank, the authenticated account is used.
6. Tap `Connect Twitch`.
7. On your phone or computer, open the activation URL shown by the app and enter the displayed code.

## Karoo usage

1. Install the APK on the device.
2. Add the `Twitch Chat` graphical field to one of your profiles.
3. Resize it to fill the page if you want the full-screen chat view.

## Build

This project vendors the `karoo-ext` library source locally in `lib/`, so it does not rely on the GitHub Packages dependency.

```bash
GRADLE_USER_HOME=/tmp/kchat-gradle ./gradlew app:assembleDebug
```

## Notes

- Twitch chat access is currently implemented with the `user:read:chat` scope.
- The Karoo view is refreshed as a buffered feed rather than per-message instant updates.
- If the EventSub socket disconnects, the app reconnects and recreates the subscription on the next session.

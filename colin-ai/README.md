# Colin AI

Colin's own AI assistant for Android.

**Install:** download [`ColinAI.apk`](../ColinAI.apk), open it on your phone, allow "install unknown apps" when asked.
On first launch, paste an Anthropic API key (get one at https://console.anthropic.com/settings/keys).

## What it does

- **Three brains:** Colin Pro (Claude Opus 5, default), Colin Ultra (Claude Fable 5.1, the most capable), Colin Fast (Claude Sonnet 5).
- **Adjustable effort:** from Low to Max, depending on how hard it should think.
- **Live web search** with clickable sources.
- **Learns about you:** saves lasting facts to long-term memory on its own and uses them in every chat.
- **Training screen:** add or delete memories and write custom instructions that shape its behaviour.
- **Voice:** speak your question and have answers read aloud.
- **Show reasoning:** peek at a summary of how it thought.
- Chat history, retry, copy, Markdown formatting, and sharing text from other apps into Colin AI.
- Your API key is stored encrypted on the phone. Chats and memory never leave the device except when sent to Anthropic to get a reply.

## Build it yourself

Requires JDK 17+ and the Android SDK (platform 36).

```bash
cd colin-ai
./gradlew assembleRelease        # APK -> app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest    # runs the brain loop against a mock API server
```

## Where things live

| File | Purpose |
|---|---|
| `app/src/main/java/ai/colin/app/ColinBrain.kt` | Colin AI's personality prompt, and the Claude API loop (streaming, web search, memory tool) |
| `app/src/main/java/ai/colin/app/ChatViewModel.kt` | App state: chats, memory, settings |
| `app/src/main/java/ai/colin/app/MainActivity.kt` | UI: chat, Training, and Settings screens |
| `app/src/main/java/ai/colin/app/Store.kt` | On-device storage |

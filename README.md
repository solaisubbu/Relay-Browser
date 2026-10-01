# Relay Browser for Android

A browser with an AI agent. Type a job, and Claude or Gemini reads the page and clicks, types and navigates step by step.
It asks for your approval before anything that pays, books, buys, sells, places an order, sends, submits or deletes.

## Get the APK
1. Open the **Actions** tab. The "Build Relay Browser APK" job runs on every push and takes about 5 minutes.
2. When it shows a green tick, open it and download **RelayBrowser-apk** at the bottom. Unzip it to get `app-debug.apk`.
3. Copy the APK to your phone, open it, and allow "Install unknown apps" when Android asks.

## Or build with Android Studio
Open this folder in Android Studio, wait for Gradle sync, then use Build > Build APK(s).

## First run
Tap Settings, choose Claude or Gemini, paste your API key, and save.
Claude keys: console.anthropic.com. Gemini keys: aistudio.google.com.
Never put an API key in the code: this repository is public.

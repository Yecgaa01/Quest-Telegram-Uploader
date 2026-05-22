### An automated photo and video uploader from Meta Quest to Telegram

## Features
* **100% Automatic:** Snap a screenshot or clip a video in-game, and it lands in your Telegram chat seconds later.
* **Lightweight:** No ads, no tracking, and built with Android system APIs for background uploads. Built to use minimal battery while monitoring Quest captures.
* **Background Service:** Runs in the background without needing you to open an app every time; It starts up automatically when the Quest boots and keeps uploading photos even in sleep mode (Yeah, i know)


*Tested only on Quest 3; compatibility with other models is unverified



## 🚀 Step-by-Step Setup

### 1. Enable Developer Mode
First, you need developer access on your headset. If you haven't done this yet, just follow this [easy 4-step Reddit guide](https://www.reddit.com/r/OculusQuest/comments/17sa8n6/tutorial_quest_3_developer_mode_4_easy_steps/).

### 2. Sideload the APK
Download the latest `.apk` from the [Releases](../../releases) tab. Next, install a sideloader of your choice on your PC/Mac (sorry, you guys will have to google this part yourself!) and use it to transfer the APK to your Quest.

### 3. Create your Telegram Bot
1. Message [@BotFather](https://t.me/BotFather) on Telegram, type `/newbot`, and follow the steps to get your **Bot Token**.
2. Create a private group or channel, add your new bot as an **Administrator**, and get the **Chat ID** (you can use a bot like `@userinfobot` to find it).

### 4. Configuration in VR
1. Put your headset on, go to your App Library, and filter by **Unknown Sources** (top right dropdown).
2. Open the app, paste your **Bot Token** and **Chat ID**, grant file access permissions, and turn the service **ON**.

# D Byk Reply (Android)

A floating ✦ bubble over WhatsApp. Tap it in a customer chat → it reads the visible messages, checks Shopify (catalogue, prices, orders) and Zoho Books (real stock per store), drafts a reply with Claude, and **Insert** types it into WhatsApp's message box. It never sends — you check it and tap send.

Same logic as the D Byk Reply Assistant Chrome extension.

## Get the app
Every push to `main` builds the app on GitHub: **Actions → latest "Build APK" run → Artifacts → DBykReply-apk**. Unzip it to get `DBykReply.apk`.

## Install on the phone
1. Copy `DBykReply.apk` to the phone and open it. Allow "Install unknown apps" for your browser/files app when asked.
2. Open **D Byk Reply** and fill in sections 2–5 (Claude key, Shopify, Zoho, store info). Test each one.
3. Turn on the bubble: **Settings → Accessibility → Installed apps → D Byk Reply bubble → On**.
   - Greyed out ("Restricted setting")? Go to **App info → ⋮ → Allow restricted settings**, then try again.
4. Open WhatsApp, open a customer chat, tap ✦.

## Use
- The bubble shows only while WhatsApp is open. Drag it anywhere.
- It reads the messages **visible on screen** — scroll so the customer's questions are showing.
- Type an instruction (e.g. "offer a test ride Saturday", "reply in Marathi") and tap Regenerate.
- **Insert into chat** → check → send. If inserting fails, it copies the reply; long-press the message box and paste.

## Good to know
- Never add auto-send — WhatsApp bans automation.
- If it stops reading chats after a WhatsApp update, the screen IDs changed (`message_text`, `entry`, `conversation_contact_name` in `BubbleService.kt`).
- Keys are stored in the app's private storage on this phone only.
- Each GitHub build is signed with a fresh development key, so to update: uninstall the old app first, install the new one, and re-enter the keys.

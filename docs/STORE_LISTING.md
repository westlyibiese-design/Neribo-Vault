# Play Store listing: Neribo Vault

## App name
Neribo Vault

## Short description (80 characters maximum)
A private notebook for notes, goals, diary, stories and more. Works offline.

(Count: 76 characters.)

## Full description

Neribo Vault is a quiet, private place for the things you write down and keep.

Everything lives in separate vaults, so your church notes never get mixed up with your code, and your diary never sits next to your to-do list.

**Your vaults**
- Notes: quick thoughts and longer writing, with tags and pinning.
- Ideas: catch them before they disappear.
- Goals: set a target date, add steps and watch your progress.
- Diary: a daily entry with mood, kept as private as you like.
- Writers: stories with chapters, characters and notes.
- Posts: plan social media posts and get a reminder when it is time.
- Church records: sermons, scriptures and what you learned on Sunday.
- Memories: moments with photos you choose.
- Documents: details of your important papers, with an optional file and an expiry reminder.
- Developer vault: projects, bugs, tasks, plans, prompts and encrypted secrets.

**Private by design**
- Works fully offline. Your data is stored on your phone.
- Lock the whole app with a PIN, and add fingerprint or face unlock if your phone supports it.
- Lock single vaults, such as your Diary, with their own PIN.
- No ads. No analytics. No tracking.
- Developer secrets are encrypted with your Secrets PIN and never leave the phone.

**Forgiving**
- Deleted something by mistake? It waits in Recently deleted for 30 days, and you can tap Undo straight away.
- Your text saves as you type.

**Your data, your choice**
- Make a password-protected backup file and keep it where you like.
- Optional cloud sync with your Google account, if you want your text on more than one phone. It is off until you sign in, you choose which vaults sync, and the Diary is off by default. Synced text is not end-to-end encrypted, and photos, files and secrets are never synced.

Made in Benin City, Nigeria, by Westly Ibiese.

## Categories
- App category: **Productivity**
- Tags to consider: Note-taking, Organizer, Diary and journal (choose from what Play Console offers)

## Contact details (required)
- Email: [add your email]
- Privacy policy URL: [publish docs/PRIVACY_POLICY.md as a web page and paste the link]

## Content rating notes (questionnaire)
- App type: utility or productivity. No violence, sexual content, gambling, drugs or profanity are provided by the app.
- Users can create and store their own text. The app does not let users share or publish content to other users inside the app. Answer the "user-generated content" question accordingly: content stays private to the user.
- No location sharing, no purchases, no ads.
- Expected rating: Everyone. Answer the questionnaire honestly on the day.

## Screenshots to prepare
Phone screenshots (at least 2, ideally 6 to 8). Use light theme for most and one in dark. Use warm, realistic Nigerian sample content, and no real private information.

| # | Screen | Suggested caption |
|---|---|---|
| 1 | Home with the vault grid | All your private notebooks in one place |
| 2 | A note being written | Write freely. It saves as you type |
| 3 | Goals list with progress | Turn plans into steps you can finish |
| 4 | Diary month calendar | A diary that stays yours |
| 5 | Lock screen with PIN pad | Locked with your PIN or fingerprint |
| 6 | Recently deleted with Undo | Deleted by mistake? Get it back |
| 7 | Dark theme Home | Easy on the eyes at night |
| 8 | Developer vault projects | Projects, tasks and secrets for builders |

Also needed: app icon (512 x 512 PNG) and a feature graphic (1024 x 500 PNG).

## Before you publish

1. **Target API level (blocker).** Google Play now requires new apps and updates to target Android 16 (API level 36). Neribo Vault currently targets API 34, so the Play Console will reject the upload. Raising it needs a newer Android Gradle Plugin and compile SDK and a check of edge-to-edge and back-gesture behaviour. That is outside this phase and needs its own phase before you can publish.
2. **Account deletion (blocker).** Because Google sign-in creates an account, Play requires an in-app way to delete it plus a web page for requests. See the end of `docs/PLAY_DATA_SAFETY.md`.
3. Pay the one-time Google Play developer account fee (US$25) and complete identity verification.
4. If you use a personal developer account, Google requires a closed test with a minimum number of testers for a minimum number of days before you can apply for production. Check the current numbers in the Play Console.
5. Enrol in Play App Signing and upload the **AAB** (`app-release.aab`), not the APK.
6. Back up the release keystore and passwords (see `docs/RELEASE.md`).
7. Run the release smoke test and the whole `docs/QA_CHECKLIST.md` on the release APK.
8. Publish the privacy policy on a public web page and fill in the contact email in it.
9. Fill in the Data safety form using `docs/PLAY_DATA_SAFETY.md`, and the content rating questionnaire.
10. Start with an internal or closed testing track, then promote to production when it is stable.

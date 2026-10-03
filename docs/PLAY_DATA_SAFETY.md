# Google Play Data safety: suggested answers

These answers match `docs/PRIVACY_POLICY.md`. Check each against the Play Console wording on the day, because Google changes the form.

**Before you answer:** confirm in the built release APK that no other permissions or SDKs were merged in. In Android Studio's Merged Manifest view, or by running `aapt dump permissions app-release.apk`, the expected permissions are INTERNET, ACCESS_NETWORK_STATE and POST_NOTIFICATIONS, plus USE_BIOMETRIC from the biometric library. If anything else appears (for example an advertising ID permission), tell me and this document must change.

## Section 1. Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes**, but only when the user turns on optional cloud sync (see below) |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS) |
| Do you provide a way for users to request that their data be deleted? | **Yes**: in the app (Settings, then Cloud sync) and on the public deletion page. See the section at the end |

## Section 2. Data types

Everything not listed below: answer **No, not collected**.

**Collected (only with cloud sync, which is optional):**

| Data type | Collected | Shared | Optional? | Purpose |
|---|---|---|---|---|
| Personal info: **Email address** | Yes | No | Yes, sync is optional | App functionality, Account management |
| Personal info: **User IDs** | Yes | No | Yes | App functionality, Account management |
| App info: **Other user-generated content** (notes, ideas, goals, diary text, stories, posts, church records, memory and document text, developer items) | Yes | No | Yes | App functionality |

**Not collected:** Name, address, phone number, financial info, health and fitness, messages, **photos and videos** (they stay on the device), **files and docs** (they stay on the device), audio, location, contacts, calendar, web browsing, app interactions, crash logs, diagnostics, device or other IDs, advertising ID.

Notes on how to answer:
- Data that is only processed on the device and never sent off the device is not "collected".
- Sending data to Supabase to host it for the user is a service provider acting for you, which Google does not count as "sharing". Answer **No** to sharing. Google sign-in is the user's own sign-in to Google.
- Developer secrets and the activity log never leave the device, so they are not listed.

## Section 3. Other declarations

| Question | Answer |
|---|---|
| Is data processed ephemerally? | No (synced data is stored until deleted) |
| Does the app follow the Families policy? | Not applicable. The app is not designed for children |
| Independent security review | No |
| Data used for advertising or analytics | No |
| Data sold | No |

## Account deletion requirement: done in the app

Google Play requires apps that let people create an account to offer account deletion **inside the app** and a **web link** where people can ask for deletion.

- **In the app:** Settings, then Cloud sync, then "Delete my cloud data and account" (added in Phase 18). It deletes the synced data and the account, and leaves the data on the phone alone.
- **Web link:** publish `web/delete-account/index.html` as a public page, replace its `[CONTACT EMAIL]` placeholder, and paste that page's link into the Play Console's account deletion question.
- Run `supabase/neribo_vault_account_deletion.sql` in the Supabase project before relying on the button.

# Google Play Data safety: suggested answers

These answers match `docs/PRIVACY_POLICY.md`. Check each against the Play Console wording on the day, because Google changes the form.

**Before you answer:** confirm in the built release APK that no other permissions or SDKs were merged in. In Android Studio's Merged Manifest view, or by running `aapt dump permissions app-release.apk`, the expected permissions are INTERNET, ACCESS_NETWORK_STATE and POST_NOTIFICATIONS, plus USE_BIOMETRIC from the biometric library. If anything else appears (for example an advertising ID permission), tell me and this document must change.

## Section 1. Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes**, but only when the user turns on optional cloud sync (see below) |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS) |
| Do you provide a way for users to request that their data be deleted? | **Yes**, by emailing the contact address in the privacy policy. See the warning at the end about account deletion |

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

## Warning: account deletion requirement

Google Play requires apps that let people create an account to offer account deletion **inside the app** and a **web link** where people can ask for deletion. Signing in with Google creates an account record in the Neribo cloud. The app does not yet have an in-app "Delete my cloud data" button (the Cloud screen says so). You need to fix this before publishing, either by adding that feature in a new phase, or by removing cloud sync from the Play version. Until then the "yes, users can request deletion" answer is only true through email.

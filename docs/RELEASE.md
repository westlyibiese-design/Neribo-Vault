# Releasing Neribo Vault

Do these steps in order, one at a time. You only need to do steps 1 to 7 once. After that, only step 8 onward for every new release.

Your ordinary debug build is not affected by any of this. It keeps building on every push and keeps its own key.

## Part A. Make the release key (once)

### Step 1. Run the keystore workflow
1. Open your repository on GitHub, then the **Actions** tab.
2. In the left list, tap **Generate release keystore**.
3. Tap **Run workflow**.
4. Choose a strong password (at least 8 characters, no spaces). Type it in **both** boxes. It must be identical.
5. Tap the green **Run workflow** button and wait for a green tick.

The password you type here is shown to people who can see the run's inputs. That is fine for a private repository you alone use. Do not reuse a password from anywhere else.

### Step 2. Download and unzip the keystore
1. Open the finished run, scroll to **Artifacts**, tap **neribo-release-keystore**. It downloads as a ZIP.
2. The file is deleted from GitHub after 1 day, so do the next steps today.
3. Unzip it on your phone. You get one file: `neribo-release.keystore`.

### Step 3. Back it up now (do not skip)
1. Save `neribo-release.keystore` in at least two safe places, for example your email drafts to yourself and a cloud drive.
2. Write down the password, and the key alias, which is `neribo`.
3. See "Why the backup matters" at the bottom.

### Step 4. Turn the keystore into text
1. Upload `neribo-release.keystore` to your Replit workspace root.
2. In the Replit Shell run:
   ```
   base64 -w0 neribo-release.keystore
   ```
3. Select and copy the whole long line of text it prints. This is the value for the first secret below.

### Step 5. Create the four repository secrets
1. On GitHub open your repository, then **Settings**, then **Secrets and variables**, then **Actions**.
2. Tap **New repository secret** and add these four, one at a time:

| Secret name | What goes in it |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | The long text you copied in step 4 |
| `RELEASE_KEYSTORE_PASSWORD` | The password you chose in step 1 |
| `RELEASE_KEY_ALIAS` | `neribo` |
| `RELEASE_KEY_PASSWORD` | The same password again |

The secrets `SUPABASE_URL`, `SUPABASE_ANON_KEY` and `GOOGLE_WEB_CLIENT_ID` that your debug build already uses are reused by the release build. Nothing to add for those.

### Step 6. Delete the keystore from Replit
After the secrets are saved, delete `neribo-release.keystore` from the Replit workspace so it is not committed by mistake. Never commit it to the repository.

## Part B. Build a release

### Step 7. Run the release workflow
1. **Actions** tab, then **Build Neribo Vault release**, then **Run workflow**.
2. **version_name**: for example `1.0.0`.
3. **version_code**: leave empty to use the run number, or type a whole number. Every upload to Google Play needs a higher number than the one before.
4. Tap **Run workflow**. If a secret is missing, the run stops and tells you which one.

### Step 8. Download the result
1. Open the finished run, then **Artifacts**, then **NeriboVault-release**.
2. Unzip. You get:
   - `app-release.apk` to install on your phone for testing.
   - `app-release.aab` to upload to Google Play.
   - `mapping.txt` which lets crash traces be read later. Keep it with each release.

### Step 9. Smoke test the release APK
The release build is shrunk by R8, which can break things that work in debug. Test before you publish anything.

1. Uninstall the debug app first. The debug and release keys are different, so one cannot install over the other. Back up your data first (Settings, Backup).
2. Install `app-release.apk`.
3. In order: create the app PIN, add a note and a goal, add a memory with a photo, add a document with a short reminder, set a secret with a PIN, run a search, schedule a post reminder two minutes ahead, create a backup and restore it.
4. If anything crashes, send the error to the person helping you. It usually means one more R8 keep rule is needed.

## Why the backup matters

Google Play only accepts updates signed with the same key as the first upload. If you lose the keystore or its password and you are not using Play App Signing, you can never update the app under the same listing.

**Recommended:** when you create the app in the Play Console, enrol in **Play App Signing**. Google then holds the final signing key, and the key you made here becomes only an upload key that can be reset if you lose it. Still back it up.

Never share the keystore or its passwords, and never commit them.

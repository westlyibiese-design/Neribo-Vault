# Neribo Vault: end-to-end QA checklist

Run this on a phone, ideally on the release APK. Tick each line. Run it in light theme, then repeat the "Look and feel" section in dark theme.

## Install and launch
- [ ] Fresh install opens with the splash, then the logo animation, with no white flash in light or dark mode.
- [ ] Onboarding appears on first run and lets you create the app PIN.
- [ ] Installing a new build over the old one keeps all data (debug builds only).
- [ ] Android 13 and up: with themed icons on, the launcher icon is tinted and shows the logo shape.

## Home
- [ ] Every vault tile opens its vault and back returns to Home.
- [ ] The search pill opens Search. The Recent tab opens Recent. Settings opens Settings.
- [ ] Rotating the phone and returning from another app keeps you where you were.

## App lock and vault locks
- [ ] Closing and reopening the app asks for the PIN after the chosen delay.
- [ ] A wrong PIN is rejected. Biometric unlock works if turned on.
- [ ] Change PIN works and the old PIN stops working.
- [ ] Lock a vault (for example Diary): opening it asks for its PIN. Unlock works.
- [ ] A locked vault's items are hidden from Search and Recent.

## Notes
- [ ] New note autosaves; "Saving…" then "Saved" appears; leaving an empty new note discards it.
- [ ] Tags, pin and archive work. Search inside Notes finds text.
- [ ] Delete from the list and from the editor shows "Moved to Recently deleted" with Undo.
- [ ] Recently deleted: Restore, Delete forever (with confirmation) and Empty trash work.

## Ideas
- [ ] Create, edit, tag, search, delete with Undo, restore from Recently deleted.

## Goals
- [ ] Create a goal with a target date and steps. Ticking steps updates progress.
- [ ] The due line shows the right days left or overdue.
- [ ] Delete with Undo, restore, Delete forever.

## Diary
- [ ] Create an entry for today and for a past date with a mood and tags.
- [ ] The month calendar marks days with entries and opens the right entry.
- [ ] Diary privacy option (blocking screenshots) works if turned on.
- [ ] Delete with Undo, restore.

## Writers
- [ ] Create a story, add chapters, edit a chapter, rename a chapter.
- [ ] Add characters, story notes and writing ideas. Progress updates.
- [ ] Delete a story with Undo. Restore brings back its chapters.

## Posts
- [ ] Create a post for a platform with hashtags. Character count behaves.
- [ ] Schedule a reminder two minutes ahead: the notification arrives. Tapping it opens the app.
- [ ] Past or invalid times are handled with a clear message.
- [ ] Delete with Undo, restore.

## Church records
- [ ] Create a record with date, speaker, scripture suggestions and tags.
- [ ] Speakers sheet works. Search and filters work.
- [ ] Delete with Undo, restore.

## Memories
- [ ] Add a memory with one or more photos from the Photo Picker.
- [ ] Open the memory, open a photo full screen, remove a photo.
- [ ] Delete with Undo. Restore brings the photos back.

## Documents
- [ ] Add a document with fields, an expiry date, an attached photo and a PDF.
- [ ] The viewer shows the image and the PDF. Share or open works.
- [ ] Set a reminder a few minutes ahead: notification arrives.
- [ ] Expiry status shows ok, expiring soon or expired correctly.
- [ ] Delete with Undo, restore.

## Developer vault
- [ ] Home shows projects. Create, edit and open a project.
- [ ] Bugs: create, change status, delete with Undo.
- [ ] Tasks: create, set a reminder, mark done, delete with Undo.
- [ ] Plans and folder plans: create, edit, open a folder tree.
- [ ] Prompts: create, use variables, favourite, duplicate.
- [ ] Project documents: create and edit.
- [ ] Secrets: set the Secrets PIN, add a secret, reveal it, copy it. The clipboard clears after a short time.
- [ ] A secret on screen blocks screenshots. Wrong Secrets PIN is rejected.
- [ ] Secret activity log shows reveals and copies.
- [ ] Recently deleted for the Developer vault: restore and Delete forever.

## Search and Recent
- [ ] Search finds items across all unlocked vaults, with the right vault shown on each result.
- [ ] Recent search terms appear and can be tapped.
- [ ] Recent shows what you changed lately, grouped by day, and opens the item.

## Settings
- [ ] Theme: system, light and dark all apply immediately.
- [ ] Security: app PIN, biometrics, auto-lock delay and screenshot option work.
- [ ] Storage shows sizes and its actions work.
- [ ] Diagnostics appears only in debug builds, not in the release build.

## Backup and restore
- [ ] Create a backup with a password of at least the minimum length. A .nvbackup file is saved where you choose.
- [ ] Mismatched or too-short passwords are rejected with a message.
- [ ] Restore with the right password shows what is inside, then restores after confirmation.
- [ ] Restore with the wrong password fails safely and changes nothing.
- [ ] After restore and restart, notes, photos, documents and secrets are all present.

## Cloud sync (only if this build has the Neribo cloud)
- [ ] Signed out: everything works and nothing is uploaded.
- [ ] Google sign-in works. A cancelled sign-in shows no error.
- [ ] Turn vaults on and off. Diary is off by default.
- [ ] Sync now: a change on one phone shows up on the other. Photos and files do not sync, only text.
- [ ] Offline: the app keeps working and syncs when back online.
- [ ] Sign out keeps all data on the phone and stops syncing.

## Look and feel
- [ ] Largest phone font size: Home, Notes, Goals and Settings show no cut-off or overlapping text.
- [ ] A 360dp-wide phone shows no clipped buttons.
- [ ] Every icon-only button is announced by TalkBack with a clear name.
- [ ] Rotation inside every editor keeps what you typed.
- [ ] Killing the app from recents while an editor is open and reopening it loses nothing.

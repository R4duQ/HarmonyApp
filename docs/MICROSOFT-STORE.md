# Harmony for Windows in the Microsoft Store

From the Store, Harmony installs with no "Windows protected your PC" warning: Microsoft
signs the package itself after checking it. Harmony Connect's network ports are opened on
install (home and work networks only), so Windows doesn't ask about the firewall either.

CI builds the Store package on every Windows build: the `Harmony-Windows-Store-1.0.N.0`
artifact of the **Harmony for Windows** workflow run, holding `Harmony-Windows-1.0.N.0.msix`.
It is not attached to the release, because it is only installable once the Store has
signed it.

## Once: the developer account and the name

1. Sign in at https://storedeveloper.microsoft.com with a Microsoft account and register as
   an **individual developer** (free; Microsoft checks your identity).
2. In Partner Center: **Apps and games → New product → MSIX or PWA app**, and reserve the
   name **Harmony** (or another, if that one is taken).
3. Open the product's **Product identity** page and copy three values into
   `desktop/msix/store-identity.properties`:
   - `Package/Identity/Name` → `IDENTITY_NAME`
   - `Package/Identity/Publisher` → `PUBLISHER` (starts with `CN=`)
   - `Package/Properties/PublisherDisplayName` → `PUBLISHER_DISPLAY_NAME`

   Push to main; the next build's MSIX carries them.

## Each release: the submission

1. Download the `Harmony-Windows-Store-…` artifact from the latest **Harmony for Windows**
   run on main and unzip it.
2. Partner Center → the product → **Start submission**:
   - **Packages**: upload the `.msix`.
   - **Properties**: category *Music*. Privacy policy URL:
     https://github.com/R4duQ/HarmonyApp/blob/main/docs/PRIVACY.md
   - **Age ratings**: fill in the questionnaire (no user-generated content, no purchases).
   - **Store listings**: description, and at least one screenshot (1366×768 or larger).
   - **Submission options → Notes for certification**: "Harmony plays local music files.
     Harmony Connect receives music from the Harmony Android app on the same network after
     pairing with a 4-digit code; the firewall rules cover only its ports, TCP 47800 and
     UDP 47801, on private networks. runFullTrust is needed because this is a desktop app
     (Java runtime bundled) that runs ffmpeg to decode audio."
3. Submit. Certification usually takes from a few hours to three working days. After
   that, every install and update comes from the Store, signed by Microsoft.

The package uses `runFullTrust`, a restricted capability; the note above explains it to
the reviewers, as Partner Center asks.

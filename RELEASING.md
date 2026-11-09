# Releasing Latlo

Releases are built by GitHub Actions (`.github/workflows/release.yml`). Pushing a tag such as
`v0.3.0` builds a signed APK and creates a **draft** pre-release with it and the notes from
`.github/release-notes.md`. Nothing is public until you open the draft on GitHub and press
**Publish release**.

## One-time setup: the signing key

Android only installs an update over an existing app if both are signed with the same key, so
create one key and keep it safe. If it's lost, testers have to uninstall before installing a
build signed with a new key.

1. Create a keystore (on any computer with Java or Android Studio; Android Studio's own Java is
   in its `jbr/bin` folder):

   ```sh
   keytool -genkeypair -v -keystore latlo-release.jks -alias latlo \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

   It asks for a password and a name; the name can be anything.

2. Turn it into text for GitHub:
   - macOS: `base64 -i latlo-release.jks | pbcopy` (copies it)
   - Linux: `base64 -w0 latlo-release.jks`
   - Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("latlo-release.jks")) | Set-Clipboard`

3. In the repository on GitHub, go to **Settings > Secrets and variables > Actions > New
   repository secret** and add:

   | Name | Value |
   |---|---|
   | `LATLO_KEYSTORE_BASE64` | the text from step 2 |
   | `LATLO_KEYSTORE_PASSWORD` | the keystore password |
   | `LATLO_KEY_ALIAS` | `latlo` |
   | `LATLO_KEY_PASSWORD` | the same password (keytool uses one password for both by default) |

4. Back up `latlo-release.jks` and the password somewhere safe (a password manager works).
   Never commit it; `.gitignore` already ignores `*.jks`.

## Making a release

1. Make sure `main` has what you want to release.
2. Tag it and push the tag:

   ```sh
   git tag v0.3.0
   git push origin v0.3.0
   ```

   Don't create the release from GitHub's **Draft a new release** page: the workflow makes it.
3. Wait for the **Build APK** run under **Actions** to finish (about 5 minutes).
4. Open **Releases**, check the draft (notes, APK attached), edit the notes if you like, and
   press **Publish release**.

The version name comes from the tag (`v0.3.0` becomes `0.3.0`) and the version code from the
workflow's run number, so each build is newer than the last.

Every pull request also builds an APK; it's attached to the run under **Actions > the run >
Artifacts**, which is handy for trying a change before it's released.

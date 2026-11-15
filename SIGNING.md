# Upgradeable APK signing (one-time setup)

Android updates require the **same applicationId**, **same signing certificate**, and a **higher versionCode**. The repository now uses the fixed package ID `dev.vadinyt.liquidnotify`, a GitHub-run-based version code, and a release keystore supplied via GitHub Actions secrets.

## Create your private signing key (once)

On a trusted computer with Java installed:

```sh
keytool -genkeypair -v -keystore liquidnotify-release.jks -alias liquidnotify -keyalg RSA -keysize 3072 -validity 10000
```

Choose and securely store the keystore and key passwords. Keep the .jks file backed up offline. Do not upload it to the public repository or send it in chat.

Base64 encode the keystore locally:
- Linux: `base64 -w0 liquidnotify-release.jks`
- macOS: `base64 < liquidnotify-release.jks | tr -d '\\n'`
- Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("liquidnotify-release.jks"))`

In GitHub repository Settings > Secrets and variables > Actions, add four **repository secrets**:

- `LIQUIDNOTIFY_KEYSTORE_BASE64`: base64-encoded keystore contents
- `LIQUIDNOTIFY_STORE_PASSWORD`: keystore password
- `LIQUIDNOTIFY_KEY_ALIAS`: `liquidnotify`
- `LIQUIDNOTIFY_KEY_PASSWORD`: private key password

Run GitHub Actions. Download the **release** APK artifact. Future release builds signed with the same key can install over one another.

## Important migration note

Previously installed debug APKs use a different certificate. **One final uninstall is necessary** when switching from an old debug APK to the first signed release APK. After that, no uninstall should be needed for subsequent signed releases. If you want to retain existing settings, export them before uninstalling.

Never lose or rotate this keystore without planning an Android signing-key migration. The workflow deliberately fails when secrets are missing instead of producing misleading upgradeable APKs.

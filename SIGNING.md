# One-secret signing setup (Termux / Android)

The GitHub Actions workflow needs exactly one **repository secret** named `LIQUIDNOTIFY_SIGNING`.

Its value consists of two lines:
1. The existing PKCS12 keystore password
2. The Base64-encoded contents of `~/liquidnotify-release.jks`

The key alias is fixed as `liquidnotify`. The PKCS12 private key password must equal the keystore password.

## Copy the bundle to the Android clipboard

In Termux (requires Termux:API):

```sh
read -rsp 'Keystore password: ' LN_PASSWORD; printf '\n'
{ printf '%s\n' "$LN_PASSWORD"; base64 -w 0 ~/liquidnotify-release.jks; } | termux-clipboard-set
unset LN_PASSWORD
```

Do not paste the bundle into chat, issues, commits or any public page. The clipboard holds sensitive data; clear it after configuring GitHub.

Open repository Settings > Secrets and variables > Actions > New repository secret. Name: `LIQUIDNOTIFY_SIGNING`. Paste the clipboard contents as its value.

Run Actions > Build LiquidNotify signed APK > Run workflow. Download the artifact `LiquidNotify-signed-upgradeable`.

Android upgrades require the same package ID, signing certificate and a higher version code. The previous debug-signed APK will require **one final uninstall** to switch to the signed release series. Keep a private backup of your keystore and password.

**Security note:** If a keystore was exposed publicly or in chat, treat it as compromised and generate a replacement before distributing public APKs. This is particularly important for an open-source repository.

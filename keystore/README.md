# Nightly test signing key

`nightly-test.jks` is a self-signed, **non-secret** test certificate used only to
sign CI and nightly APK builds (see `.github/workflows/`) so that consecutive
builds share a signature and can be installed over one another without
uninstalling.

It is **not** a production/Play Store signing key. Its password is
intentionally checked in alongside it in `nightly-test.properties` — treat
both files as public. Never reuse this key for a real release build.

Regenerate it (if it is ever lost or needs rotating) with:

```bash
keytool -genkeypair -v \
  -keystore keystore/nightly-test.jks \
  -storetype PKCS12 \
  -alias health-devices-nightly \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass health-devices-nightly-test -keypass health-devices-nightly-test \
  -dname "CN=Health Devices Nightly Test, OU=Retinue OS, O=Retinue OS, L=Unknown, ST=Unknown, C=US"
```

# PuheTrack 2.14.4 release checklist

## Automated gate

- [x] `:app:testDebugUnitTest` — 20/20 tests pass.
- [x] `:app:lintRelease` — zero errors.
- [x] `:app:assembleRelease` — minified APK produced.
- [x] `:app:bundleRelease` — Play AAB produced and signed.
- [x] APK signature verified; certificate matches the configured PuheTrack upload identity.
- [x] AAB JAR signature verified.
- [x] APK 16 KB zip alignment verified.
- [x] All ARM64 ELF `LOAD` alignments are `0x4000`.
- [x] Release artifact hashes recorded below.

## Product gate

- [ ] Every P0 test in `TESTING.md` passed on a physical device.
- [ ] Offline model install/resume/inference passed.
- [ ] Live Finnish → English passed with the target phone's recognition service.
- [ ] OpenAI and selected cloud translator passed with real credentials, if those providers will be advertised.
- [ ] Three-hour soak evidence reviewed.
- [ ] Upgrade from the last installed version preserved existing files.
- [ ] Ed/Astra audit has no unresolved P0/P1 finding.

## Distribution gate

- [ ] Version 2.14.4 / code 26 is unused in the destination Play track.
- [ ] Privacy disclosure accurately lists audio/text sent to configured cloud providers.
- [ ] Data-safety form matches backup exclusions and app-private storage behavior.
- [ ] Exact third-party license texts/attributions are packaged from `THIRD_PARTY_NOTICES.md` inventory.
- [x] `RELEASE_NOTES.md` describes recorded Live Translate, its Android 13+ requirement, and exact 30-minute audio rotation.
- [ ] Internal testing precedes production rollout; staged rollout/monitoring owner is assigned.

## Final artifacts

Fill these only from the final build:

```text
PuheTrack-2.14.4.apk  size: 84,301,797 bytes
SHA-256: ebf29cbc0d29e4b153f2b5eaeb266a11a321c6f24a8347402124f8481e0d7f09

PuheTrack-2.14.4.aab  size: 68,677,636 bytes
SHA-256: 29c109e1068b9a0cad24b3d5cc2f71727eb0b45bda26519aefaff9a8e44d2598

signing subject: CN=PuheTrack, OU=PuheTrack, O=PuheTrack, L=Turku, ST=Finland, C=FI
certificate SHA-256: 8203b25c9b0035077781f9d4a56142da5fb7de7c25803c7c0a4c2ee3cb6f1bb5
```

The implementation and automated release gate are complete. Unchecked physical/provider/distribution items require the owner's phone, accounts, real elapsed time or Play delivery and must not be represented as passed.

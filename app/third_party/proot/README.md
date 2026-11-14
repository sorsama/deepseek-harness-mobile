# proot for the built-in harness runtime

`app/src/main/jniLibs/<abi>/libproot.so` and `libproot-loader.so` are not built in this
repository. They are release assets of the runtime repository, copied here byte for byte:

| | |
|---|---|
| Release | [`2026.11.14-1`](https://github.com/sorsama/dsh-mobile-runtime/releases/tag/2026.11.14-1) of `sorsama/dsh-mobile-runtime` |
| Assets | `android-<abi>-libproot.so` and `android-<abi>-libproot-loader.so`, renamed to the `installAs` names the release manifest gives |
| Hashes | [`SHA256SUMS`](SHA256SUMS), the same values as the release's `SHA256SUMS.txt` and its signed `manifest.json` |
| Source | [`termux/proot`](https://github.com/termux/proot) at commit `52d65a4dc140ca554d241a1438ddd4f6b00c9813` (proot 5.1.107.102), with [talloc](https://talloc.samba.org) 2.5.0 linked statically |
| Build | NDK 28.2.13676358, API 26, `-z max-page-size=16384`; see `scripts/build-proot-android.sh` in the runtime repository |
| Exact sources | `proot-source-52d65a4dc140ca554d241a1438ddd4f6b00c9813.tar.gz` and `talloc-source-2.5.0.tar.gz`, attached to the same release |
| Licenses | proot: GPL-2.0-or-later. talloc: LGPL-3.0-or-later. See [`THIRD_PARTY_NOTICES.md`](../../../THIRD_PARTY_NOTICES.md). |

Only the two 64-bit ABIs the runtime is built for are shipped. Each binary has only Android's
`libc`, `libdl` and `libm` as dependencies, no RPATH, and no Termux path compiled in; every
`LOAD` segment is 16 KB aligned.

`ProotBinariesTest` (`app/src/test`) fails the unit tests if a committed binary does not match
`SHA256SUMS`, so the files cannot drift from this record unnoticed.

## Updating

1. Download the four `android-*` assets of the new release and check them with its
   `SHA256SUMS.txt`.
2. Copy them over the files in `app/src/main/jniLibs/<abi>/` under their `installAs` names.
3. Update `SHA256SUMS`, the release, commit and hashes above, and `THIRD_PARTY_NOTICES.md`.

The layers (Debian, Node.js, the harness) are not in the APK; the app downloads them at run
time and checks them against the signed manifest. See `docs/ARCHITECTURE.md`.

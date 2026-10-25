# Exact-version dependency sources

This directory accompanies DreamGlass 1.0.4 corresponding source. It contains the published source JARs for all 57 resolved runtime components and the compile-only LibXposed API (58 coordinates total). Source copyrights and license headers remain inside the unmodified archives.

manifest.json records each coordinate, canonical download URL, actual download URL and SHA-256. The interface/service sources match the local 102.0.0 AARs; only their AAR minCompileSdk metadata was modified, as documented in app/libs/README.md.

These source archives are distribution materials, not application resources or additional runtime dependencies. Gradle resolves the declared binaries as described in docs/BUILDING.md. Android SDK, compilers and other general build tools are not included here.

Recreate with `python scripts/fetch_dependency_sources.py --output third_party_sources` from the project root. License details: ../THIRD-PARTY-NOTICES.md and ../licenses/.

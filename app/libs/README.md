# Local LibXposed artifacts

Components: io.github.libxposed:interface:102.0.0 and io.github.libxposed:service:102.0.0.
License: Apache-2.0. Official source: https://github.com/libxposed/service .

Modified for this project: only META-INF/com/android/build/gradle/aar-metadata.properties,
changing minCompileSdk=37 to minCompileSdk=36. Classes, resources and manifests are preserved.
This metadata edit does not claim Android SDK 37 compatibility for every API.
The application uses only APIs verified against its declared API 33+ runtime requirements.

The matching AARs are included in the source distribution so builds need no private repository.
To reconstruct them, download the 102.0.0 AARs from Maven Central and copy all ZIP entries
unchanged except that single property value. Do not redistribute any signing keys.
The original Apache license text is included in ../../licenses/Apache-2.0.txt.

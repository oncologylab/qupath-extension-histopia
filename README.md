# Histopia for QuPath

The Histopia extension connects QuPath 0.7 to
[Histopia](https://github.com/oncologylab/histopia) registration and global
semantic-atlas results.

## Capabilities

- run registration and global semantic-atlas configs without blocking QuPath
- stream Python progress and cancel the active process
- launch a validated, compact Histopia interchange export
- select registration and optional semantic result directories
- discover every available K and default to Histopia's selected K
- select one bundle manifest and automatically import the matching open slide
- verify schema-2 annotation checksums before import
- preserve Histopia region classes and colors, with optional replacement of
  previous Histopia annotations

The extension does not run registration or UNI2-h inside QuPath's JVM. It calls
the selected Python installation, where `histopia` and the required optional
dependencies must already be installed.

## Build

```bash
./gradlew build
```

Install the JAR from `build/libs` using QuPath's extension manager or by
dragging it into QuPath. Open **Extensions > Histopia > Open Histopia tools**.

The **Run analysis** tab accepts Histopia TOML or JSON configs for registration
and semantic analysis. The **Export and import** tab writes a QuPath bundle,
loads the available semantic K values, and imports annotations for the
currently open matching source slide.

Semantic annotations are exported in original WSI pixel coordinates. Histopia
thumbnail registration matrices are included as provenance and are not applied
directly to QuPath native coordinates. Adjacent same-class patches are
losslessly coalesced by default to reduce GeoJSON size and QuPath geometry
overhead; the Python CLI retains a one-tile-per-patch audit mode.

See the
[Histopia QuPath integration guide](https://github.com/oncologylab/histopia/blob/main/docs/qupath.md)
for Python installation, bundle guarantees, and CLI usage.

## Requirements

- QuPath 0.7
- Java version supported by QuPath
- Python 3.10 or later with Histopia installed

## License

This extension is licensed under GPL-3.0-or-later to remain compatible with
QuPath. Histopia has its own license.

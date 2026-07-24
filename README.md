# Histopia for QuPath

The Histopia extension connects QuPath 0.7 to
[Histopia](https://github.com/oncologylab/histopia) registration and global
semantic-atlas results.

## Capabilities

- launch a validated Histopia interchange export without blocking QuPath
- select registration and optional semantic result directories
- choose any K available in the semantic result
- select one bundle manifest and automatically import the matching open slide
- preserve Histopia region classes and colors

The extension does not run registration or UNI2-h inside QuPath's JVM. It calls
the selected Python installation, where `histopia` and the required optional
dependencies must already be installed.

## Build

```bash
./gradlew build
```

Install the JAR from `build/libs` using QuPath's extension manager or by
dragging it into QuPath. Open **Extensions > Histopia > Open Histopia tools**.

Semantic annotations are exported in original WSI pixel coordinates. Histopia
thumbnail registration matrices are included as provenance and are not applied
directly to QuPath native coordinates.

## Requirements

- QuPath 0.7
- Java version supported by QuPath
- Python 3.10 or later with Histopia installed

## License

This extension is licensed under GPL-3.0-or-later to remain compatible with
QuPath. Histopia has its own license.

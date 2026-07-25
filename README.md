# Histopia for QuPath

[![Build](https://github.com/oncologylab/qupath-extension-histopia/actions/workflows/build.yml/badge.svg)](https://github.com/oncologylab/qupath-extension-histopia/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/oncologylab/qupath-extension-histopia?include_prereleases)](https://github.com/oncologylab/qupath-extension-histopia/releases)

The Histopia extension connects QuPath 0.7 to
[Histopia](https://github.com/oncologylab/histopia) registration and global
semantic-atlas results.

## Capabilities

- select an exact cohort directly from the open QuPath project
- preserve project order or morphology-sort sections, with an optional fixed
  reference as the first order anchor
- configure registration, UNI2-h semantic analysis, CPU/GPU execution
  including explicit `cuda:N` selection, K range, and optional native libvips
  thread limits plus bounded global-fit threads without authoring config files
- choose conservative automatic registration and QC worker counts capped at
  four, then tune preprocessing and memory-heavier QC rendering independently
- validate the selected Histopia workflow API, Python dependencies, native
  libvips runtime, and compute device directly from the extension
- run workflow-specific environment preflight automatically before
  registration, semantic analysis, and interchange export
- run registration and global semantic-atlas configs without blocking QuPath
- stream Python progress, redact review notes from the command log, and cancel
  the complete Python process tree with bounded force escalation
- build and open one local staged mask/order QC portal
- record separate fingerprint-bound mask and order approvals before final
  registration sealing
- verify the final seal's artifact checksums and review fingerprint before
  displaying a sealed state or launching semantic analysis
- reject approval or semantic launch when the current QuPath selection differs
  from the prepared or sealed registration cohort
- build and open the semantic 3D/QC viewer, then record fingerprint-bound
  semantic approval
- launch a validated, compact Histopia interchange export
- select registration and optional semantic result directories
- discover every available K and default to Histopia's selected K
- select one bundle manifest and automatically import the matching open slide
- verify schema-2/3/4 annotation checksums and byte sizes before import
- reject schema-3 bundles with stale approval, mismatched provenance, or
  annotation paths and symlinks outside the bundle
- require matching final registration-approval metadata for schema-4 bundles
- preserve Histopia region classes and colors, with optional replacement of
  previous Histopia annotations

The extension does not run registration or UNI2-h inside QuPath's JVM. It calls
the selected Python installation, where `histopia` and the required optional
dependencies must already be installed.

## Install

Install the current Python workflow in the environment that the extension will
use:

```bash
python -m pip install \
  "histopia[registration,wsi,uni2h,qupath] @ git+https://github.com/oncologylab/histopia.git@main"
```

Download `qupath-extension-histopia-0.3.12.jar` from the
[latest release](https://github.com/oncologylab/qupath-extension-histopia/releases/latest).
Drag the JAR onto QuPath 0.7, restart QuPath, then open
**Extensions > Histopia > Open Histopia tools**.

The release also includes a SHA-256 checksum file. Verify it before installing:

```bash
sha256sum --check qupath-extension-histopia-0.3.12.jar.sha256
```

## Build From Source

```bash
./gradlew build
```

The installable JAR is written under `build/libs`.

The **Project workflow** tab lists supported local WSI entries from the open
QuPath project. Select at least two slides, choose a workspace and optional
reference, then run registration. The first run prepares masks; open QC,
review, and choose **Approve masks**. Run again to prepare morphology-aware
order, review it, and choose **Approve order**. A third run computes alignment.
After reviewing it, choose **Seal reviewed run** before starting the semantic
atlas. When semantic analysis completes, choose **Open semantic QC**, review
the atlas, and choose **Approve semantic** before export. Runtime configs, the
exact project-selection manifest, and local review portals are kept in
`<workspace>/.histopia`. The semantic viewer is served only on an ephemeral
`127.0.0.1` port so browser modules and local assets load correctly.

Enter `auto`, `cpu`, `cuda`, `cuda:N`, or `mps` in the semantic device
control. **Check environment** runs the versioned `histopia-qupath` doctor in
the selected Python environment and streams dependency, libvips, backend, and
accelerator details to the extension log. Registration, semantic analysis, and
export run their narrower preflight automatically before starting. Leave
**VIPS threads**
blank for libvips' adaptive default, or enter a positive cap shared by
registration WSI reads and semantic extraction after benchmarking the target
scanner format, storage, and host. The automatic registration-worker value uses
half the available processors up to a maximum of four; it remains editable for
measured host-specific tuning of thumbnail, mask, and section-order work.
On one validated 17,280 x 17,664 SCN export, eight native threads completed in
5.17 seconds at 598 MiB peak RSS, while sixteen took 5.25 seconds at 839 MiB;
the generated TIFFs were byte-identical. This is measured guidance, not a
portable default.
**QC workers** independently bounds concurrent registration diagnostics so an
eight-worker mask setting does not force eight memory-heavier QC renderers. In
a measured 24-slide, 1200-pixel mask run, eight workers used 1.62 GB peak
memory and was the practical throughput setting; 16 workers used 2.55 GB for
only another 1.29x speedup. **Fit threads** separately caps native BLAS and
OpenMP work during the global semantic fit; four is the measured conservative
default.

The **Run analysis** tab remains available for advanced TOML or JSON configs.
The **Export and import** tab writes a QuPath bundle, loads the available
semantic K values, and imports annotations for the currently open matching
source slide.

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
- project entries backed by one local NDPI, SCN, SVS, TIFF, or OME-TIFF URI

## License

This extension is licensed under GPL-3.0-or-later to remain compatible with
QuPath. Histopia has its own license.

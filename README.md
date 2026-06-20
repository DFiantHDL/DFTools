# DFTools

**Reproducible, source-pinned binary release of the open-source EDA toolchain
[DFHDL](https://github.com/DFiantHDL/dfhdl) depends on**, packaged as a single
[Apptainer](https://apptainer.org/) image and run via
[Scalapptainer](https://github.com/DFiantWorks/Scalapptainer).

DFHDL runs these tools through DFTools **by default**; users can opt into local
installations with `--tools-location local`. The DFTools release cycle is
independent of the DFHDL library release: when a tool pin is bumped, CI rebuilds the
image and tests it against the latest DFHDL release **and** `main` — and only then
publishes new binaries.

## What's in the image

| Category | Tools |
|---|---|
| Verilog/SV simulation | `verilator`, `iverilog` (`vvp`) |
| VHDL simulation | `ghdl`, `nvc` |
| Synthesis | `yosys` + `ghdl-yosys-plugin` (`yosys -m ghdl`) |
| Place & route | `nextpnr-ecp5` (Lattice), `nextpnr-himbaechel` (Gowin) |
| Bitstream | `ecppack` (Project Trellis), `gowin_pack` (Apicula) |
| Programmer | `openFPGALoader` |
| Formal equivalence | `eqy` |
| Waveform viewer | `surfer` (GUI; X11-forwarded) |

Proprietary tools (Vivado, Quartus, Diamond, Gowin Designer, QuestaSim) are **not**
included — DFHDL uses those only via `--tools-location local`.

## Repository layout

| Path | Purpose |
|---|---|
| [`pins.env`](pins.env) | **Single source of truth** for source revisions (tag/commit per tool) |
| [`DFTools.def`](DFTools.def) | Multi-stage Apptainer recipe: heavy `build` stage → minimal runtime stage |
| [`build/strip-runtime.sh`](build/strip-runtime.sh) | Prunes build-only artifacts and strips binaries |
| [`scripts/build.sc`](scripts/build.sc) | Scalapptainer build driver (`.def` + pins → `.sif`) |
| [`scripts/test.sc`](scripts/test.sc) | Validates a built `.sif` (image self-test + DFHDL gate) |
| [`.github/workflows/release.yml`](.github/workflows/release.yml) | build → test → publish pipeline |

## How the minimal image works

`DFTools.def` is a two-stage build:

1. **`build` stage** — full toolchains (gcc/clang, gnat, LLVM, Rust, cmake, boost,
   …). Each tool is cloned at its `pins.env` revision and installed into
   `/opt/dftools`. The prefix is then pruned and stripped (`build/strip-runtime.sh`).
2. **final stage** — copies **only** `/opt/dftools` from the build stage and installs
   just the runtime shared libraries. No compilers, source trees, headers, static
   libraries, or build artifacts reach the published `.sif`.

The LLVM backend is used for `ghdl` and `nvc` so the same recipe builds on both
`linux-x64` and `linux-arm64`.

## Reproducibility & pinning

Every tool is built from a specific tag or commit recorded in `pins.env` — never a
moving branch. To update a tool, change its line in `pins.env`; the diff drives the
build → test → publish pipeline. After the first green CI build, commit-pinned
revisions that point at a branch head should be hardened to the exact built SHA.

## Building locally

Requires Apptainer (native Linux) or, on Windows/macOS, the WSL2/Lima backend that
Scalapptainer provisions. Build from the repository root:

```bash
scala-cli run scripts/build.sc -- dftools.sif        # real root (CI / Docker)
DFTOOLS_NONROOT=1 scala-cli run scripts/build.sc -- dftools.sif   # unprivileged dev build
```

Validate a built image:

```bash
scala-cli run scripts/test.sc -- dftools.sif [path/to/dfhdl/checkout]
```

## Releases

Each release `vX.Y.Z` attaches, per architecture:
`dftools-X.Y.Z-<arch>.sif`, its `.sha256`, and a `MANIFEST.txt` (tool → pinned
revision). DFHDL pulls the `.sif` matching its baked-in DFTools version.

## License

DFTools packaging (recipe, scripts, CI) is **Apache-2.0**. The bundled tools retain
their own upstream licenses.

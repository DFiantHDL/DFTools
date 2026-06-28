# DFTools

**Reproducible, source-pinned binary releases of the open-source EDA toolchain
[DFHDL](https://github.com/DFiantHDL/dfhdl) depends on**, packaged as a set of
[Apptainer](https://apptainer.org/) images and run via
[Scalapptainer](https://github.com/DFiantWorks/Scalapptainer).

DFHDL runs these tools through DFTools by default; users can opt into local
installations with `--tools-location local`. The DFTools release cycle is
independent of the DFHDL library release: bumping a tool pin rebuilds and retests
**only the image(s) that contain it**, and publishes only if the tests pass.

## Images

Tools are clustered by shared heavy dependency / hard linkage, so a tool bump
rebuilds the smallest possible image:

| image | tools | notes |
|---|---|---|
| **synth-verilog** | yosys (+ yosys-slang SV frontend), eqy | no VHDL/LLVM — stays lean |
| **synth-vhdl** | yosys + ghdl (frontend) + ghdl-yosys-plugin | `yosys -m ghdl`; carries LLVM |
| **pnr** | nextpnr-ecp5, nextpnr-himbaechel, ecppack, gowin_pack | consumes yosys JSON |
| **sim-llvm** | nvc + ghdl (simulator) | the two VHDL sims share one LLVM |
| **sim-verilator** | verilator (+ g++/make/perl) | keeps a C++ build env at runtime¹ |
| **sim-iverilog** | iverilog, vvp | small, self-contained |
| **wavegen** | surfer | GUI; X11-forwarded |
| **program** | openFPGALoader | small |

¹ Verilator emits C++ that is compiled into the simulation **at use-time inside the
image**, so per Verilator's docs `g++`/`make`/`perl` and the `libfl`/`zlib`/`lz4`
dev headers are runtime requirements (only verilator's own `autoconf`/`flex`/`bison`
are stripped).

**ghdl appears in two images on purpose**: the *synthesis frontend* ghdl
(`synth-vhdl`, plugin-ABI-bound to yosys) and the *simulator* ghdl (`sim-llvm`) are
pinned independently (`GHDL_SYNTH_REV` / `GHDL_SIM_REV`), so a simulator bump never
perturbs the synthesis image and vice versa. Proprietary tools (Vivado, Quartus,
Diamond, Gowin Designer, QuestaSim) are out of scope — used only via
`--tools-location local`.

## Layout

| path | purpose |
|---|---|
| [`pins.env`](pins.env) | single source of truth for source revisions (tag/commit per tool) |
| [`images/*.def`](images/) | one multi-stage Apptainer recipe per image |
| [`build/common.sh`](build/common.sh) | shared build helpers (clone, venv, manifest) |
| [`build/strip-runtime.sh`](build/strip-runtime.sh) | prune build-only artifacts; strip binaries |
| [`scripts/build.sc`](scripts/build.sc) | `build.sc <image> [dest.sif]` — Scalapptainer build driver |
| [`scripts/test.sc`](scripts/test.sc) | `test.sc probe <image> <sif>` / `test.sc dfhdl <sif-dir> <dfhdl-dir>` |
| [`scripts/affected-images.sh`](scripts/affected-images.sh) | maps a diff to the affected image(s) |
| [`scripts/lockfile.sc`](scripts/lockfile.sc) | rename sifs to immutable names + (re)generate `dftools.lock.json` |
| [`.github/workflows/release.yml`](.github/workflows/release.yml) | detect → build+test → DFHDL gate → publish |

## How each image stays minimal

Every `.def` is a two-stage build: a heavy `build` stage installs full toolchains and
installs the tools into `/opt/dftools`; the prefix is pruned and stripped
(`build/strip-runtime.sh`), then a minimal final stage copies **only** `/opt/dftools`
and installs just the runtime shared libraries (no compilers, sources, or headers —
except `sim-verilator`, which intentionally keeps a C++ build env). The base
(`ubuntu:24.04`, ~28 MB) is the only OS overhead per image; SIF has no cross-image
layer sharing, so each image carries its own slim base — kept small relative to the
tool payloads (LLVM, yosys, trellis DB, …).

The LLVM backend is used for `ghdl`/`nvc` so the recipes build on both `linux-x64`
and `linux-arm64`.

## Reproducibility & pinning

Every tool is built from a specific tag or commit in `pins.env` — never a moving
branch. Change one line to bump a tool; CI rebuilds only the affected image(s). After
the first green build, harden branch-head commit pins to exact SHAs.

## Building & testing locally

Requires Apptainer (native Linux) or, on Windows/macOS, the WSL2/Lima backend that
Scalapptainer provisions. From the repository root:

```bash
scala-cli run scripts/build.sc -- synth-verilog                 # real root (CI/Docker)
DFTOOLS_NONROOT=1 scala-cli run scripts/build.sc -- synth-verilog   # unprivileged dev build
scala-cli run scripts/test.sc  -- probe synth-verilog dftools-synth-verilog.sif
```

## Releases

Each release tag holds a [`dftools.lock.json`](scripts/lockfile.sc) plus the per-image
assets. Asset names are **immutable and content-addressed** —
`dftools-<image>-<arch>-<sha12>.sif` (+ `.sha256` and `.MANIFEST.txt`) — so a published
asset is never mutated in place: a changed image lands under a new name and old tags stay
valid.

The lockfile is the tag's **only** version-keyed artifact. It maps each image+arch to the
sha256 of its sif and the asset carrying those bytes:

```json
{ "tag": "v0.2.0",
  "images": { "sim-verilator": { "linux-x64": { "sha256": "ab12…",
                                                 "asset": "dftools-sim-verilator-linux-x64-ab12….sif" } } } }
```

DFHDL bundles this lockfile at build time and resolves/caches each image **by sha256**, not
by tag — so bumping the tag re-downloads only the image(s) whose digest actually changed, and
the rest of the set is a cache hit. Only rebuilt images are refreshed on a re-publish; the
lockfile is regenerated (seeded from the prior one on the tag) to point at the current set.

## License

DFTools packaging (recipes, scripts, CI) is **Apache-2.0**. Bundled tools retain their
own upstream licenses.

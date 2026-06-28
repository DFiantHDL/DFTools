#!/bin/sh
# Print the DFTools images affected by a change, one per line.
#
#   affected-images.sh [<base-ref> [<head-ref>]]
#
# With no base-ref, prints ALL images (full build). Otherwise diffs base..head:
#   - a change to build/common.sh, build/strip-runtime.sh, scripts/, or the workflow
#     affects ALL images;
#   - a change to images/<X>.def affects image X;
#   - a changed <KEY>_REV line in pins.env affects the image(s) that build that tool.
set -eu

ALL="synth-verilog synth-vhdl pnr sim-llvm sim-verilator sim-iverilog wavegen program hmi"

pin_images() {  # map a pins.env key to the image(s) that consume it
  case "$1" in
    YOSYS_REV)                          echo "synth-verilog synth-vhdl" ;;
    YOSYS_SLANG_REV|EQY_REV)            echo "synth-verilog" ;;
    GHDL_SYNTH_REV|GHDL_YOSYS_PLUGIN_REV) echo "synth-vhdl" ;;
    NEXTPNR_REV|PRJTRELLIS_REV|APICULA_REV) echo "pnr" ;;
    NVC_REV|GHDL_SIM_REV)               echo "sim-llvm" ;;
    VERILATOR_REV)                      echo "sim-verilator" ;;
    IVERILOG_REV)                       echo "sim-iverilog" ;;
    SURFER_REV)                         echo "wavegen" ;;
    OPENFPGALOADER_REV)                 echo "program" ;;
    *)                                  echo "" ;;
  esac
}

BASE="${1:-}"
HEAD="${2:-HEAD}"

if [ -z "$BASE" ]; then
  echo "$ALL" | tr ' ' '\n'
  exit 0
fi

changed=$(git diff --name-only "$BASE" "$HEAD")

# shared infrastructure / drivers / workflow -> rebuild everything
if echo "$changed" | grep -qE '^(build/common\.sh|build/strip-runtime\.sh|scripts/|\.github/workflows/)'; then
  echo "$ALL" | tr ' ' '\n'
  exit 0
fi

out=""
for f in $changed; do
  case "$f" in
    images/*.def) out="$out $(basename "$f" .def)" ;;
  esac
done

if echo "$changed" | grep -qx 'pins.env'; then
  keys=$(git diff "$BASE" "$HEAD" -- pins.env \
    | grep -E '^[+-][A-Z0-9_]+_REV=' \
    | sed -E 's/^[+-]([A-Z0-9_]+_REV)=.*/\1/' | sort -u)
  for k in $keys; do out="$out $(pin_images "$k")"; done
fi

echo "$out" | tr ' ' '\n' | sed '/^$/d' | sort -u

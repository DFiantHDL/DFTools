#!/bin/sh
# Shared build helpers for all DFTools image recipes. Each image .def copies this
# file plus pins.env into the build stage and sources them:
#
#   . /opt/pins.env
#   . /opt/common.sh
#
# Expects to run as root in the build stage of a Debian/Ubuntu-based image.
set -eu

export PREFIX="${PREFIX:-/opt/dftools}"
export PATH="$PREFIX/bin:$PREFIX/pyenv/bin:$PATH"
NPROC="$(nproc)"
export NPROC
mkdir -p "$PREFIX" /src

# clone <url> <rev> <dir> [--recursive]
# Detached checkout at an exact revision (tag or SHA) for reproducibility.
clone() {
  _url="$1"; _rev="$2"; _dir="$3"; _rec="${4:-}"
  git clone $_rec "$_url" "/src/$_dir"
  git -C "/src/$_dir" checkout --detach "$_rev"
  if [ "$_rec" = "--recursive" ]; then
    git -C "/src/$_dir" submodule update --init --recursive
  fi
}

# Create (once) and return the shared Python venv used by tools that need a Python
# runtime at use-time (apicula/gowin_pack). Idempotent.
py_venv() {
  if [ ! -x "$PREFIX/pyenv/bin/python" ]; then
    python3 -m venv "$PREFIX/pyenv"
    "$PREFIX/pyenv/bin/pip" install --no-cache-dir --upgrade pip wheel
  fi
}

# Best path to llvm-config on this image (versioned or unversioned).
llvm_config() {
  command -v llvm-config 2>/dev/null || ls /usr/bin/llvm-config-* | sort -V | tail -1
}

# Write the per-image build manifest (image name + the pins it was built from).
write_manifest() {  # write_manifest <image-name>
  {
    echo "# DFTools image: $1"
    echo "arch=$(uname -m)"
    grep -E '_REV=' /opt/pins.env || true
  } > "$PREFIX/MANIFEST.txt"
}

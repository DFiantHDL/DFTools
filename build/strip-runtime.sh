#!/bin/sh
# Prune build-only artifacts that may have been captured under the install prefix,
# keeping only what is needed to *run* the tools. Idempotent.
#
# Usage: strip-runtime.sh <prefix>   (default: /opt/dftools)
set -eu
PREFIX="${1:-/opt/dftools}"

# Dev headers and static/libtool archives — link-time only. NOTE: only the top-level
# include/ dir is removed; do NOT blanket-delete *.h, since some tools ship runtime
# headers under share/ (e.g. Verilator's share/verilator/include, compiled at use-time).
rm -rf "$PREFIX/include"
find "$PREFIX" -type f \( -name '*.a' -o -name '*.la' \) -delete 2>/dev/null || true

# Docs / man / info / locale — not needed at runtime.
rm -rf "$PREFIX/share/man" "$PREFIX/share/doc" "$PREFIX/share/info" \
       "$PREFIX/share/locale" "$PREFIX/share/gtk-doc"

# pkg-config / cmake package metadata — build-time discovery only.
rm -rf "$PREFIX/lib/pkgconfig" "$PREFIX/lib64/pkgconfig" "$PREFIX/share/pkgconfig" \
       "$PREFIX/lib/cmake" "$PREFIX/lib64/cmake"

# Python: drop caches, test suites, and pip itself from the runtime venv.
find "$PREFIX" -type d -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true
find "$PREFIX" -type d \( -name 'tests' -o -name 'test' \) -path '*/site-packages/*' \
     -prune -exec rm -rf {} + 2>/dev/null || true
rm -rf "$PREFIX"/pyenv/lib/python*/site-packages/pip \
       "$PREFIX"/pyenv/lib/python*/site-packages/pip-* \
       "$PREFIX"/pyenv/lib/python*/site-packages/setuptools* \
       "$PREFIX"/pyenv/bin/pip* 2>/dev/null || true

# Strip symbols from ELF binaries/shared objects to shrink the image.
find "$PREFIX/bin" "$PREFIX/lib" "$PREFIX/lib64" -type f 2>/dev/null \
    | while IFS= read -r f; do
        case "$(head -c4 "$f" 2>/dev/null)" in
            "$(printf '\177ELF')") strip --strip-unneeded "$f" 2>/dev/null || true ;;
        esac
      done

echo "strip-runtime: done ($PREFIX)"
du -sh "$PREFIX" 2>/dev/null || true

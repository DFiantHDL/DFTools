//> using scala 3.8.4
//> using dep io.github.dfiantworks::scalapptainer:0.2.1
//> using dep com.lihaoyi::os-lib:0.11.4

// Build one DFTools image from images/<image>.def + pins.env using Scalapptainer.
//
// Run from the repository root (the %files paths in the .def are resolved relative to
// the build context):
//
//   scala-cli run scripts/build.sc -- <image> [dest.sif]
//
// where <image> is one of: synth-verilog synth-vhdl pnr sim-llvm sim-verilator
//                          sim-iverilog wavegen program
//
// In CI (real Linux runner with root) the build runs with real root. For an unprivileged
// local/dev build set DFTOOLS_NONROOT=1 (slower emulated-root build).

import scalapptainer.*

def baseName(p: String): String =
  val n = p.replace('\\', '/').split('/').last
  val dot = n.lastIndexOf('.')
  if dot > 0 then n.substring(0, dot) else n

val images = Set(
  "synth-verilog", "synth-vhdl", "pnr", "sim-llvm",
  "sim-verilator", "sim-iverilog", "wavegen", "program"
)

require(args.nonEmpty, s"usage: build.sc <image> [dest.sif]; images: ${images.mkString(" ")}")
val image = args(0)
require(images.contains(image), s"unknown image '$image'; known: ${images.mkString(" ")}")
val dest = args.lift(1).getOrElse(s"dftools-$image.sif")
val nonRoot = sys.env.get("DFTOOLS_NONROOT").exists(v => v == "1" || v.equalsIgnoreCase("true"))

println(s"[dftools] building $image -> $dest (nonRoot=$nonRoot) ...")
val img = Apptainer.build(
  source = s"images/$image.def",
  name = s"dftools-$image",
  dest = Some(dest),
  force = true,
  enableNonRootBuild = nonRoot
)
println(s"[dftools] built ${img.path}")

val manifest = img.exec("cat", "/opt/dftools/MANIFEST.txt").throwIfFailed().out
val manifestPath = os.Path(dest, os.pwd) / os.up / s"${baseName(dest)}.MANIFEST.txt"
os.write.over(manifestPath, manifest)
println(s"[dftools] wrote $manifestPath")
println(manifest)

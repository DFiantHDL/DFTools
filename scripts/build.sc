//> using scala 3.8.4
//> using dep io.github.dfiantworks::scalapptainer:0.2.1
//> using dep com.lihaoyi::os-lib:0.11.4

// Build the DFTools image from DFTools.def + pins.env using Scalapptainer.
//
// Run from the repository root (the %files paths in DFTools.def are resolved
// relative to the build context):
//
//   scala-cli run scripts/build.sc -- [dest.sif]
//
// In CI (real Linux runner with root / Docker) the build runs with real root.
// For an unprivileged local/dev build (WSL2/Lima without the uidmap helpers) set
//   DFTOOLS_NONROOT=1
// to enable Scalapptainer's emulated-root build (slower, lower fidelity).

import scalapptainer.*

@main def main(args: String*): Unit =
  val dest = args.headOption.getOrElse("dftools.sif")
  val nonRoot = sys.env.get("DFTOOLS_NONROOT").exists(v => v == "1" || v.equalsIgnoreCase("true"))

  println(s"[dftools] building $dest from DFTools.def (nonRoot=$nonRoot) ...")
  val img = Apptainer.build(
    source = "DFTools.def",
    name = "dftools",
    dest = Some(dest),
    force = true,
    enableNonRootBuild = nonRoot
  )
  println(s"[dftools] built ${img.path}")

  // Provenance: pull the in-image manifest out next to the .sif.
  val manifest = img.exec("cat", "/opt/dftools/MANIFEST.txt").throwIfFailed().out
  val manifestPath = os.Path(dest, os.pwd) / os.up / s"${baseName(dest)}.MANIFEST.txt"
  os.write.over(manifestPath, manifest)
  println(s"[dftools] wrote $manifestPath")
  println(manifest)

def baseName(p: String): String =
  val n = p.replace('\\', '/').split('/').last
  val dot = n.lastIndexOf('.')
  if dot > 0 then n.substring(0, dot) else n

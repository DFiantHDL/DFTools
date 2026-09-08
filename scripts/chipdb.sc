//> using scala 3.8.4
//> using dep io.github.dfiantworks::scalapptainer:0.5.4
//> using dep com.lihaoyi::os-lib:0.11.8

// Generate the nextpnr-xilinx chip databases the openXC7 flow places against, from inside a built
// `pnr-xilinx` image.
//
//   scala-cli run scripts/chipdb.sc -- <pnr-xilinx.sif> <out-dir> [<base-part> ...]
//
// A chipdb is `prjxray-db` compiled by `bbaexport.py` + `bbasm` into the memory-mapped binary
// nextpnr mmaps at startup. It is the slow, memory-heavy step of the whole toolchain: hours per
// family, tens of GB of `.bba` scratch, and several GB of RAM on the big parts. It can therefore
// never run on a user's machine as part of a build, and it is far too big to bake into the image
// (90-670 MB per part, 7.8 GB for the full set). So it runs here, once per release, and each `.bin`
// is published as its own immutable, content-addressed asset that DFHDL resolves on demand.
//
// A chipdb is only valid for the `nextpnr-xilinx` build that produced it (`common/nextpnr.cc`
// asserts the interned IdString table matches `xilinx/constids.inc` index for index), which is why
// this runs from the image rather than from a separate toolchain: the binary that will consume the
// chipdb is the same one whose constids built it. Bumping NEXTPNR_XILINX_REV or PRJXRAY_DB_REV
// therefore means regenerating the set.
//
// Two properties keep the set small: one chipdb serves every speed grade of a base part (the
// exporter needs a speed-graded directory only to read `package_pins.csv`, which is identical
// across grades), and the `.bin` is byte-identical across host platforms, so unlike a sif a chipdb
// asset is not per-arch.
//
// For each part this emits, into <out-dir>:
//   dftools-chipdb-<base-part>.bin.gz       the chipdb, gzipped (~3.6x)
//   dftools-chipdb-<base-part>.bin.sha256   the digest of the DECOMPRESSED chipdb
// `lockfile.sc` then renames both to their content-addressed names and records them in the
// `chipdbs` section of `dftools.lock.json`. The digest is of the decompressed bytes because that is
// what DFHDL caches and verifies (it stores the chipdb unpacked, ready to mmap).

import scalapptainer.*

// The parts DFHDL's device library can target today. Widen this as devices are added; every entry
// costs generation time in CI and one release asset, but nothing in the image.
val defaultParts = Seq(
  "xc7a100tcsg324", // Digilent Nexys A7-100T
  "xc7a35tcpg236" // Digilent Basys 3 / Arty A7-35T
)

def familyOf(basePart: String): String = basePart.take(4) match
  case "xc7a" => "artix7"
  case "xc7k" => "kintex7"
  case "xc7s" => "spartan7"
  case "xc7v" => "virtex7"
  case "xc7z" => "zynq7"
  case _      => sys.error(s"'$basePart' is not a Xilinx 7-series part name")

require(args.length >= 2, "usage: chipdb.sc <pnr-xilinx.sif> <out-dir> [<base-part> ...]")
val sif = args(0)
val outDir = os.Path(args(1), os.pwd)
val parts = if (args.length > 2) args.drop(2).toSeq else defaultParts

require(Apptainer.image(sif).exists, s"image not found: $sif")
os.makeDir.all(outDir)

// Paths inside the image, per the contract in images/pnr-xilinx.def.
val dbDir = "/opt/dftools/share/prjxray-db"
val nextpnrDir = "/opt/dftools/share/nextpnr-xilinx"

// The `.bba` intermediate is 5-30 GB per part, so it is written to a scratch directory that is
// bound in and wiped per part, never to the (published) output directory.
val work = os.temp.dir(prefix = "dftools-chipdb-")

// One handle with the scratch and output directories bound in and the scratch as $PWD; everything
// below writes through those binds rather than into the image's read-only filesystem.
val img = Apptainer.image(sif).bind(work.toString, "/work").bind(outDir.toString, "/out")
  .withOptions(_.pwd("/work"))

// Streamed, not captured: a single part can run for hours, and its progress output is the only sign
// the job is alive.
def run(cmd: String*): Unit =
  val code = img.execInteractive(cmd*)
  require(code == 0, s"failed (exit $code): ${cmd.mkString(" ")}")

try
  parts.foreach { basePart =>
    val family = familyOf(basePart)
    // The exporter reads `<part>/package_pins.csv`, so it needs a speed-graded directory; every
    // grade of a base part yields the same chipdb, so take the first the database happens to ship.
    val graded =
      img.exec("sh", "-c", s"ls -1 $dbDir/$family | grep '^$basePart-' | sort | head -1")
        .throwIfFailed().out.trim
    require(graded.nonEmpty, s"no '$basePart-*' directory under $dbDir/$family in the image")
    println(s"[dftools-chipdb] $basePart (via $graded) ...")

    val bin = s"dftools-chipdb-$basePart.bin"
    run(
      "python3",
      s"$nextpnrDir/python/bbaexport.py",
      "--device",
      graded,
      "--xray",
      s"$dbDir/$family",
      "--metadata",
      s"$nextpnrDir/external/nextpnr-xilinx-meta/$family",
      "--constids",
      s"$nextpnrDir/constids.inc",
      "--bba",
      s"/work/$basePart.bba"
    )
    run("bbasm", "-l", s"/work/$basePart.bba", s"/out/$bin")
    os.remove.all(work / s"$basePart.bba")

    // Digest and compress in the image too, so the job needs no host-side coreutils and behaves
    // identically on a developer machine and on the runner.
    img.exec(
      "sh",
      "-c",
      s"cd /out && sha256sum '$bin' > '$bin.sha256' && gzip -9 -f '$bin'"
    ).throwIfFailed()
    val sha = os.read(outDir / s"$bin.sha256").trim.split("\\s+").head
    val mb = os.size(outDir / s"$bin.gz") / 1048576
    println(s"[dftools-chipdb] $basePart sha256=${sha.take(12)} gz=${mb}MB")
  }
finally os.remove.all(work)

println(s"[dftools-chipdb] wrote ${parts.length} chip database(s) to $outDir")

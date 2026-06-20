//> using scala 3.8.4
//> using dep io.github.dfiantworks::scalapptainer:0.2.1
//> using dep com.lihaoyi::os-lib:0.11.4

// Validate a freshly-built DFTools .sif BEFORE it is published.
//
//   scala-cli run scripts/test.sc -- <dftools.sif> [<dfhdl-checkout-dir>]
//
// Stage 1 (image self-test): runs the def's %test block and a per-tool version
//          probe inside the image.
// Stage 2 (DFHDL gate): if a DFHDL checkout dir is given, runs DFHDL's
//          toolchain-touching test suites against THIS image (not PATH tools), via
//          the dev/test image-path override `-Ddfhdl.dftools.sif=<path>` and the
//          `--tools-location dftools` default. CI runs this for both the latest
//          DFHDL release tag and `main`.

import scalapptainer.*

@main def main(args: String*): Unit =
  require(args.nonEmpty, "usage: test.sc <dftools.sif> [<dfhdl-checkout-dir>]")
  val sif = args(0)
  val dfhdlDir = args.lift(1)

  val img = Apptainer.image(sif)
  require(img.exists, s"image not found: $sif")

  // --- Stage 1: image self-test + per-tool version probes --------------------
  println("[dftools-test] apptainer test (runs the %test block) ...")
  Apptainer.run(Seq("test", sif)).throwIfFailed()

  val probes = Seq(
    Seq("verilator", "--version"),
    Seq("iverilog", "-V"),
    Seq("ghdl", "version"),
    Seq("nvc", "--version"),
    Seq("yosys", "-V"),
    Seq("yosys", "-m", "ghdl", "-p", "help ghdl"),
    Seq("nextpnr-ecp5", "--version"),
    Seq("nextpnr-himbaechel", "--version"),
    Seq("openFPGALoader", "-V"),
    Seq("surfer", "--version")
  )
  val failures = probes.flatMap { cmd =>
    val r = img.exec(cmd*)
    val tag = cmd.mkString(" ")
    if r.succeeded then { println(s"  ok   : $tag"); None }
    else { println(s"  FAIL : $tag\n${r.out}"); Some(tag) }
  }
  require(failures.isEmpty, s"image probes failed: ${failures.mkString(", ")}")

  // --- Stage 2: DFHDL test matrix against this image -------------------------
  dfhdlDir match
    case None =>
      println("[dftools-test] no DFHDL checkout given; skipping the DFHDL gate.")
    case Some(dir) =>
      val root = os.Path(dir, os.pwd)
      val sifAbs = os.Path(sif, os.pwd).toString
      val sbtn = if scala.util.Properties.isWin then "sbtn.bat" else "sbtn"
      // Point DFHDL at THIS .sif and force the DFTools location, then run the
      // toolchain-touching suites. `dfhdl.dftools.sif` is the dev/test override
      // consumed by DFToolsImage; `--tools-location dftools` is the default but is
      // set explicitly here for clarity.
      val jvmOpt = s"-Ddfhdl.dftools.sif=$sifAbs"
      println(s"[dftools-test] running DFHDL testApps in $root against $sifAbs")
      val res = os.proc(sbtn, s"""set ThisBuild/javaOptions += "$jvmOpt"""", "testApps")
        .call(cwd = root, check = false, stdout = os.Inherit, stderr = os.Inherit)
      require(res.exitCode == 0, s"DFHDL testApps failed (exit ${res.exitCode})")
      println("[dftools-test] DFHDL gate passed.")

  println("[dftools-test] OK")

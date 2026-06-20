//> using scala 3.8.4
//> using dep io.github.dfiantworks::scalapptainer:0.2.1
//> using dep com.lihaoyi::os-lib:0.11.4

// Validate DFTools images before they are published. Two modes:
//
//   scala-cli run scripts/test.sc -- probe <image> <sif>
//       Run the image's %test block and per-tool version probes inside the image.
//
//   scala-cli run scripts/test.sc -- dfhdl <sif-dir> <dfhdl-checkout-dir>
//       Full DFHDL gate: discover every dftools-<image>.sif under <sif-dir>, point DFHDL
//       at each via `-Ddfhdl.dftools.sif.<image>=<path>`, and run the toolchain-touching
//       suites (testApps). CI runs this for the latest DFHDL release tag and for `main`.

import scalapptainer.*

// per-image self-test probes (bare in-image commands)
val probes: Map[String, Seq[Seq[String]]] = Map(
  "synth-verilog" -> Seq(Seq("yosys", "-V")),
  "synth-vhdl"    -> Seq(Seq("ghdl", "version"), Seq("yosys", "-V"),
                         Seq("yosys", "-m", "ghdl", "-p", "help ghdl")),
  "pnr"           -> Seq(Seq("nextpnr-ecp5", "--version"), Seq("nextpnr-himbaechel", "--version")),
  "sim-llvm"      -> Seq(Seq("ghdl", "version"), Seq("nvc", "--version")),
  "sim-verilator" -> Seq(Seq("verilator", "--version"), Seq("sh", "-c", "command -v g++ make perl")),
  "sim-iverilog"  -> Seq(Seq("iverilog", "-V")),
  "wavegen"       -> Seq(Seq("surfer", "--version")),
  "program"       -> Seq(Seq("openFPGALoader", "-V"))
)

require(args.length >= 2, "usage: test.sc probe <image> <sif> | test.sc dfhdl <sif-dir> <dfhdl-dir>")

args(0) match
  case "probe" =>
    val image = args(1)
    val sif = args(2)
    val img = Apptainer.image(sif)
    require(img.exists, s"image not found: $sif")
    println(s"[dftools-test] apptainer test ($image) ...")
    Apptainer.exec(Seq("test", sif)).throwIfFailed()
    val failures = probes.getOrElse(image, Nil).flatMap { cmd =>
      val r = img.exec(cmd*)
      val tag = cmd.mkString(" ")
      if r.succeeded then { println(s"  ok   : $tag"); None }
      else { println(s"  FAIL : $tag\n${r.out}"); Some(tag) }
    }
    require(failures.isEmpty, s"image probes failed: ${failures.mkString(", ")}")
    println(s"[dftools-test] $image OK")

  case "dfhdl" =>
    val sifDir = os.Path(args(1), os.pwd)
    val dfhdlDir = os.Path(args(2), os.pwd)
    // map each present image to its sif via the per-image override system property.
    // sif files are named dftools-<image>.sif or dftools-<image>-<arch>.sif.
    val sifs = os.list(sifDir).filter(p => p.last.endsWith(".sif"))
    val overrides = probes.keys.toSeq.flatMap { image =>
      sifs.find(p => p.last == s"dftools-$image.sif" || p.last.startsWith(s"dftools-$image-"))
        .map(p => s"-Ddfhdl.dftools.sif.$image=${p.toString}")
    }
    require(overrides.nonEmpty, s"no dftools-*.sif found under $sifDir")
    val sbtn = if scala.util.Properties.isWin then "sbtn.bat" else "sbtn"
    val jvmOpts = overrides.mkString(" ")
    println(s"[dftools-test] DFHDL gate in $dfhdlDir with:\n  ${overrides.mkString("\n  ")}")
    val res = os.proc(sbtn, s"""set ThisBuild/javaOptions ++= "$jvmOpts".split(" ").toSeq""", "testApps")
      .call(cwd = dfhdlDir, check = false, stdout = os.Inherit, stderr = os.Inherit)
    require(res.exitCode == 0, s"DFHDL testApps failed (exit ${res.exitCode})")
    println("[dftools-test] DFHDL gate passed.")

  case other =>
    sys.error(s"unknown mode '$other' (expected: probe | dfhdl)")

//> using scala 3.8.4
//> using dep com.lihaoyi::os-lib:0.11.4
//> using dep com.lihaoyi::upickle:4.4.3

// Generate (or merge) the DFTools release lockfile and rename the freshly built sifs to
// immutable, content-addressed asset names.
//
//   scala-cli run scripts/lockfile.sc -- <tag> <dist-dir>
//
// The lockfile (`dftools.lock.json`) is the umbrella release tag's ONLY version-keyed
// artifact: it maps each image+arch to the sha256 of its sif and the immutable asset that
// carries those exact bytes. Everything downstream (DFHDL's resolver, its on-disk cache) keys
// on that sha256, never on the tag — so bumping the tag re-downloads only the image(s) whose
// digest actually changed, never the rest of the set.
//
// For every freshly built `dftools-<image>-<arch>.sif` under <dist-dir> this:
//   * reads its sha256 (from the sibling `.sif.sha256` produced in the build-test job),
//   * renames the sif + its `.sha256` + `.MANIFEST.txt` to an immutable, add-only name that
//     embeds the digest: `dftools-<image>-<arch>-<sha12>.<ext>` (a different digest is a
//     different file, so a published asset is never mutated in place — old tags stay valid),
//   * records `{ sha256, asset }` under `images.<image>.<arch>` in the lockfile.
//
// The lockfile is seeded from the prior lockfile already on <tag> (best-effort over the
// network), so a partial re-publish (workflow_dispatch with only some images rebuilt) carries
// the untouched images forward. A clean version-tag build rebuilds all images, so dist alone is
// already complete.

val repo = "DFiantHDL/DFTools"
require(args.length >= 2, "usage: lockfile.sc <tag> <dist-dir>")
val tag     = args(0)
val distDir = os.Path(args(1), os.pwd)

// 1. Seed from the prior lockfile on this tag (empty on the first publish of the tag).
val lock: ujson.Obj =
  try
    val url = java.net.URI.create(
      s"https://github.com/$repo/releases/download/$tag/dftools.lock.json").toURL
    val in = url.openStream()
    try ujson.read(new String(in.readAllBytes(), "UTF-8")).obj
    finally in.close()
  catch case _: Throwable => ujson.Obj("tag" -> tag, "images" -> ujson.Obj())
lock("tag") = tag
val images = lock("images").obj

// 2. Overlay every freshly built sif, renaming it to its immutable content-addressed name.
val sifRe = raw"dftools-(.+)-(linux-(?:x64|arm64))\.sif".r
os.list(distDir).filter(p => sifRe.matches(p.last)).sortBy(_.last).foreach { sif =>
  val sifRe(image, arch) = (sif.last: @unchecked)
  val shaFile = distDir / s"${sif.last}.sha256"
  require(os.exists(shaFile), s"missing checksum for ${sif.last}: $shaFile")
  val sha  = os.read(shaFile).trim.split("\\s+").head
  val base = s"dftools-$image-$arch-${sha.take(12)}"

  // Immutable, add-only renames (sif + checksum + manifest). The sha is embedded in the name, so
  // these never collide with a different build's bytes; a re-publish of unchanged bytes re-uploads
  // an identical-named asset (a no-op), and a changed image lands under a brand-new name.
  os.move(sif, distDir / s"$base.sif", replaceExisting = true)
  os.write.over(distDir / s"$base.sif.sha256", s"$sha  $base.sif\n")
  os.remove(shaFile)
  val manifest = distDir / s"dftools-$image-$arch.MANIFEST.txt"
  if (os.exists(manifest)) os.move(manifest, distDir / s"$base.MANIFEST.txt", replaceExisting = true)

  val entry = ujson.Obj("sha256" -> sha, "asset" -> s"$base.sif")
  images.getOrElseUpdate(image, ujson.Obj()).obj(arch) = entry
  println(s"[dftools-lock] $image/$arch -> $base.sif")
}

os.write.over(distDir / "dftools.lock.json", ujson.write(lock, indent = 2))
println(s"[dftools-lock] wrote ${distDir / "dftools.lock.json"} for $tag")
println(ujson.write(lock, indent = 2))

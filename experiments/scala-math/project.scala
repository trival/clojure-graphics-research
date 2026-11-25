// Same settings as trivalibs-scala/project.scala, except smallestmodules:
// one ES module per class, so each kernel's emitted JS can be read on its own.
//> using scala 3.9.0
//> using platform js
//> using option -Wconf:msg=differs.only.in.case:s
//> using option -feature
//> using option -language:implicitConversions
//> using option -Yexplicit-nulls

//> using jsVersion 1.22.0
//> using jsMode full
//> using jsModuleKind es
//> using jsEsVersionStr es2021
//> using jsModuleSplitStyleStr smallestmodules

//> using dep org.scala-js::scalajs-dom::2.8.1

Stolen wholesale from Paul Phillips' code (http://xuwei-k.github.io/scala-compiler-sxr/scala-compiler-2.9.1/scala/tools/util/SignalManager.scala.html and related files), with minor modifications made to make it compatible with scala 2.11 (mostly involving import statemts)

example usage:

Include the dependency in your `build.sbt` / `Build.scala` / what-have-you
```
  resolvers += "silasmariner" at "http://dl.bintray.com/hughsimpson/maven"
  ...
  libraryDependencies += "org.silasmariner" %% "sigterms" % "0.1.0"
```
Import'n'use

```
  import sigterms.SignalManager
  SignalManager.requireInterval(60, SignalManager.HUP) { // Custom response to `kill -1` message
    case true => println("Closing all IO!")
    case false => // 60s grace period not over. Do nothing.
  }
```

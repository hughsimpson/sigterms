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

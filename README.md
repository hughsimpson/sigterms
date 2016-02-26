example usage:

```
  libraryDependencies += "org.hughsimpson" %% "sigterms" % "0.1.0"
```

```
  import sigterms.SignalManager
  SignalManager.requireInterval(60, SignalManager.HUP) { // Custom response to `kill -1` message
    case true => println("Closing all IO!")
    case false => // 60s grace period not over. Do nothing.
  }
```

package sigterms

import java.lang.reflect.Constructor
import scala.reflect.internal.util.ScalaClassLoader
import scala.tools.nsc.util.Exceptional, Exceptional.unwrap
import scala.util.Random

import java.lang.reflect.{ Method, Proxy, InvocationHandler }

object Util {
  def nameAndArity(m: Method) = (m.getName, m.getParameterTypes.size)
  def allInterfaces(cl: Class[_]): List[Class[_]] =
    if (cl == null) Nil
    else cl.getInterfaces.toList ++ allInterfaces(cl.getSuperclass) distinct
}

/** A class representing a single method call.  It is primarily for use
  *  in tandem with Mock.  If the invocation did not target an InvocationHandler,
  *  proxy will be null.
  */
class Invoked private (val proxy: AnyRef, val m: Method, val args: List[AnyRef]) {
  def name                 = m.getName
  def arity                = m.getParameterTypes.size
  def returnType           = m.getReturnType
  def returns[T: Manifest] = returnType == manifest[T].erasure

  def invokeOn(target: AnyRef) = m.invoke(target, args: _*)
  def isObjectMethod = Set("toString", "equals", "hashCode") contains name

  override def toString = "Invoked: %s called with %s".format(
    m.getName,
    if (args.isEmpty) "no args" else "args '%s'".format(args mkString ", ")
  )
}

object Invoked {
  def apply(m: Method, args: Seq[Any]): Invoked = apply(null, m, args)
  def apply(proxy: AnyRef, m: Method, args: Seq[Any]): Invoked = {
    val fixedArgs = if (args == null) Nil else args.toList map (_.asInstanceOf[AnyRef])
    new Invoked(proxy, m, fixedArgs)
  }
  def unapply(x: Any) = x match {
    case x: Invoked => Some(x.proxy, x.m, x.args)
    case _          => None
  }
  object NameAndArgs {
    def unapply(x: Any) = x match {
      case x: Invoked => Some(x.name, x.args)
      case _          => None
    }
  }
  object NameAndArity {
    def unapply(x: Any) = x match {
      case x: Invoked => Some(x.name, x.arity)
      case _          => None
    }
  }
}


/** A wrapper around java dynamic proxies to make it easy to pose
  *  as an interface.  See SignalManager for an example usage.
  */
trait Mock extends (Invoked => AnyRef) {
  mock =>

  def interfaces: List[Class[_]]
  def classLoader: ClassLoader
  def apply(invoked: Invoked): AnyRef

  def newProxyInstance(handler: InvocationHandler): AnyRef =
    Proxy.newProxyInstance(classLoader, interfaces.toArray, handler)
  def newProxyInstance(): AnyRef =
    newProxyInstance(newInvocationHandler())

  def newInvocationHandler() = new InvocationHandler {
    def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]) =
      mock(Invoked(proxy, method, args))
  }
}

/** The methods in Mock create the actual proxy instance which can be used
  *  in place of the associated interface(s).
  */
object Mock {
  /** The default implementation calls the partial function if defined, and
    *  routes Object methods to the proxy: otherwise it throws an exception.
    */
  def fromInterfaces(clazz: Class[_], clazzes: Class[_]*)(pf: PartialFunction[Invoked, AnyRef]): AnyRef = {
    val ints = clazz :: clazzes.toList
    require(ints forall (_.isInterface), "All class objects must represent interfaces")

    val mock = new Mock {
      val interfaces  = ints
      def classLoader = clazz.getClassLoader
      def apply(invoked: Invoked) =
        if (pf.isDefinedAt(invoked)) pf(invoked)
        else if (invoked.isObjectMethod) invoked invokeOn this
        else throw new NoSuchMethodException("" + invoked)
    }
    mock.newProxyInstance()
  }
  /** Tries to implement all the class's interfaces.
    */
  def fromClass(clazz: Class[_])(pf: PartialFunction[Invoked, AnyRef]): AnyRef = Util.allInterfaces(clazz) match {
    case Nil      => sys.error(clazz + " implements no interfaces.")
    case x :: xs  => fromInterfaces(x, xs: _*)(pf)
  }
}

trait Shield {
  def className: String
  def classLoader: ScalaClassLoader

  // Override this if you are more ambitious about logging or throwing.
  def onError[T >: Null](msg: String): T = null

  /** This is handy because all reflective calls want back an AnyRef but
    *  we will often be generating Units.
    */
  protected implicit def boxedUnit(x: Unit): AnyRef = scala.runtime.BoxedUnit.UNIT

  lazy val clazz: Class[_] = classLoader.tryToLoadClass(className) getOrElse onError("Failed to load " + className)
  lazy val methods = clazz.getMethods.toList

  def constructor(paramTypes: Class[_]*) = clazz.getConstructor(paramTypes: _*).asInstanceOf[Constructor[AnyRef]]
  def method(name: String, arity: Int)   = uniqueMethod(name, arity)
  def field(name: String)                = clazz getField name

  def matchingMethods(name: String, arity: Int) = methods filter (m => Util.nameAndArity(m) == (name, arity))
  def uniqueMethod(name: String, arity: Int) = matchingMethods(name, arity) match {
    case List(x)  => x
    case _        => onError("No unique match for " + name)
  }
}

/** Signal handling code.  100% clean of any references to sun.misc:
  *  it's all reflection and proxies and invocation handlers and lasers,
  *  so even the choosiest runtimes will be cool with it.
  *
  *  Sun/Oracle says sun.misc.* is unsupported and therefore so is all
  *  of this.  Simple examples:
  *  {{{
      val manager = sigterms.SignalManager // or you could make your own
      // Assignment clears any old handlers; += chains them.
      manager("HUP") = println("HUP 1!")
      manager("HUP") += println("HUP 2!")
      // Use raise() to raise a signal: this will print both lines
      manager("HUP").raise()
      // See a report on every signal's current handler
      manager.dump()
  *  }}}
  */
class SignalManager(classLoader: ScalaClassLoader) {
  def this() = this(ScalaClassLoader.appLoader)
  private val illegalArgHandler: PartialFunction[Throwable, Boolean] = {
    case x if unwrap(x).isInstanceOf[IllegalArgumentException] => false
  }
  private def fail(msg: String) = new SignalError(msg)

  object rSignalHandler extends Shield {
    val className   = "sun.misc.SignalHandler"
    val classLoader = SignalManager.this.classLoader

    lazy val SIG_DFL = field("SIG_DFL") get null
    lazy val SIG_IGN = field("SIG_IGN") get null

    /** Create a new signal handler based on the function.
      */
    def apply(action: Invoked => Unit) = Mock.fromInterfaces(clazz) {
      case inv @ Invoked.NameAndArgs("handle", _ :: Nil) => {action(inv) ; scala.runtime.BoxedUnit.UNIT}
    }
    def empty = rSignalHandler(_ => ())
  }
  import rSignalHandler.{ SIG_DFL, SIG_IGN }

  object rSignal extends Shield {
    val className   = "sun.misc.Signal"
    val classLoader = SignalManager.this.classLoader

    lazy val handleMethod = method("handle", 2)
    lazy val raiseMethod  = method("raise", 1)
    lazy val numberMethod = method("getNumber", 0)

    /** Create a new Signal with the given name.
      */
    def apply(name: String)                     = constructor(classOf[String]) newInstance name
    def handle(signal: AnyRef, current: AnyRef) = {
      if (signal == null || current == null) fail("Signals cannot be null")
      else handleMethod.invoke(null, signal, current)
    }
    def raise(signal: AnyRef)                   = {
      if (signal == null) fail("Signals cannot be null")
      else raiseMethod.invoke(null, signal)
    }
    def number(signal: AnyRef): Int             = numberMethod.invoke(signal).asInstanceOf[Int]

    class WSignal(val name: String) {
      lazy val signal             = rSignal apply name
      def number                  = rSignal number signal
      def raise()                 = rSignal raise signal
      def handle(handler: AnyRef) = rSignal.handle(signal, handler)

      def isError               = false
      def setTo(body: => Unit)  = register(name, false, body)
      def +=(body: => Unit)     = register(name, true, body)

      /** It's hard to believe there's no way to get a signal's current
        *  handler without replacing it, but if there is I couldn't find
        *  it, so we have this swapping code.
        */
      def withCurrentHandler[T](f: AnyRef => T): T = {
        val swap = handle(rSignalHandler.empty)

        try f(swap)
        finally handle(swap)
      }
      def isDefault = try withCurrentHandler {
        case SIG_DFL  => true
        case _        => false
      } catch illegalArgHandler
      def isIgnored = try withCurrentHandler {
        case SIG_IGN  => true
        case _        => false
      } catch illegalArgHandler
      def isSetTo(ref: AnyRef) =
        try withCurrentHandler { _ eq ref }
        catch illegalArgHandler

      def handlerString() = withCurrentHandler {
        case SIG_DFL    => "Default"
        case SIG_IGN    => "Ignore"
        case x          => "" + x
      }

      override def toString = "%10s  %s".format("SIG" + name,
        try handlerString()
        catch { case x: Exception => "VM threw " + unwrap(x) }
      )
      override def equals(other: Any) = other match {
        case x: WSignal => name == x.name
        case _          => false
      }
      override def hashCode = name.##
    }
  }
  type WSignal = rSignal.WSignal

  /** Adds a handler for the named signal.  If shouldChain is true,
    *  the installed handler will call the previous handler after the
    *  new one has executed.  If false, the old handler is dropped.
    */
  private def register(name: String, shouldChain: Boolean, body: => Unit) = {
    val signal  = rSignal(name)
    val current = rSignalHandler(_ => body)
    val prev    = rSignal.handle(signal, current)

    if (shouldChain) {
      val chainer = rSignalHandler { inv =>
        val signal = inv.args.head

        inv invokeOn current
        prev match {
          case SIG_IGN | SIG_DFL  => ()
          case _                  => inv invokeOn prev
        }
      }
      rSignal.handle(signal, chainer)
      chainer
    }
    else current
  }

  /** Use apply and update to get and set handlers.
    */
  def apply(name: String): WSignal =
    try   { new WSignal(name) }
    catch { case x: IllegalArgumentException => new SignalError(x.getMessage) }

  def update(name: String, body: => Unit): Unit = apply(name) setTo body

  class SignalError(message: String) extends WSignal("") {
    override def isError = true
    override def toString = message
  }

  def public(name: String, description: String)(body: => Unit): Unit = {
    try {
      val wsig = apply(name)
      if (wsig.isError)
        return

      wsig setTo body
      registerInfoHandler()
      addPublicHandler(wsig, description)
    }
    catch {
      case x: Exception => ()   // ignore failure
    }
  }
  /** Makes sure the info handler is registered if we see activity. */
  private def registerInfoHandler() = {
    val INFO = apply("INFO")
    if (publicHandlers.isEmpty && INFO.isDefault) {
      INFO setTo Console.println(info())
      addPublicHandler(INFO, "Print signal handler registry on console.")
    }
  }
  private def addPublicHandler(wsig: WSignal, description: String) = {
    if (publicHandlers contains wsig) ()
    else publicHandlers = publicHandlers.updated(wsig, description)
  }
  private var publicHandlers: Map[WSignal, String] = Map()
  def info(): String = {
    registerInfoHandler()
    val xs = publicHandlers.toList sortBy (_._1.name) map {
      case (wsig, descr) => "  %2d  %5s  %s".format(wsig.number, wsig.name, descr)
    }

    xs.mkString("\nSignal handler registry:\n", "\n", "")
  }
}

object SignalManager extends SignalManager {
  private implicit def mkWSignal(name: String): WSignal = this(name)
  private lazy val signalNumberMap = all map (x => x.number -> x) toMap

  def all = List(
    HUP, INT, QUIT, ILL, TRAP, ABRT, EMT, FPE,    // 1-8
    KILL, BUS, SEGV, SYS, PIPE, ALRM, TERM, URG,  // 9-15
    STOP, TSTP, CONT, CHLD, TTIN, TTOU, IO, XCPU, // 16-23
    XFSZ, VTALRM, PROF, WINCH, INFO, USR1, USR2   // 24-31
  )
  /** Signals which are either inaccessible or which seem like
    *  particularly bad choices when looking for an open one.
    */
  def reserved         = Set(QUIT, TRAP, ABRT, KILL, BUS, SEGV, ALRM, STOP, INT)
  def unreserved       = all filterNot reserved
  def defaultSignals() = unreserved filter (_.isDefault)
  def ignoredSignals() = unreserved filter (_.isIgnored)
  def findOpenSignal() = Random.shuffle(defaultSignals()).head

  def dump() = all foreach (x => println("%2s %s".format(x.number, x)))

  def apply(sigNumber: Int): WSignal = signalNumberMap(sigNumber)

  def HUP: WSignal    = "HUP"
  def INT: WSignal    = "INT"
  def QUIT: WSignal   = "QUIT"
  def ILL: WSignal    = "ILL"
  def TRAP: WSignal   = "TRAP"
  def ABRT: WSignal   = "ABRT"
  def EMT: WSignal    = "EMT"
  def FPE: WSignal    = "FPE"
  def KILL: WSignal   = "KILL"
  def BUS: WSignal    = "BUS"
  def SEGV: WSignal   = "SEGV"
  def SYS: WSignal    = "SYS"
  def PIPE: WSignal   = "PIPE"
  def ALRM: WSignal   = "ALRM"
  def TERM: WSignal   = "TERM"
  def URG: WSignal    = "URG"
  def STOP: WSignal   = "STOP"
  def TSTP: WSignal   = "TSTP"
  def CONT: WSignal   = "CONT"
  def CHLD: WSignal   = "CHLD"
  def TTIN: WSignal   = "TTIN"
  def TTOU: WSignal   = "TTOU"
  def IO: WSignal     = "IO"
  def XCPU: WSignal   = "XCPU"
  def XFSZ: WSignal   = "XFSZ"
  def VTALRM: WSignal = "VTALRM"
  def PROF: WSignal   = "PROF"
  def WINCH: WSignal  = "WINCH"
  def INFO: WSignal   = "INFO"
  def USR1: WSignal   = "USR1"
  def USR2: WSignal   = "USR2"

  /** Given a number of seconds, a signal, and a function: sets up a handler which upon
    *  receiving the signal once, calls the function with argument true, and if the
    *  signal is received again within the allowed time, calls it with argument false.
    *  (Otherwise it calls it with true and starts the timer over again.)
    */
  def requireInterval(seconds: Long, wrapper: WSignal)(fn: Boolean => Unit) = {
    var received = false
    wrapper setTo {
      if (received) fn(false)
      else {
        received = true
        fn(true)
        new java.util.Timer(true).schedule(new java.util.TimerTask{ def run() = received = false }, seconds * 1000L)
      }
    }
  }
}

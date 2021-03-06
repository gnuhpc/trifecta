package com.github.ldaniels528.trifecta

import java.io.PrintStream

import com.github.ldaniels528.trifecta.io.AsyncIO
import com.github.ldaniels528.trifecta.modules.core.CoreModule
import com.github.ldaniels528.trifecta.modules.kafka.KafkaSandbox
import org.apache.zookeeper.KeeperException.ConnectionLossException
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.postfixOps
import scala.tools.jline.console.ConsoleReader
import scala.tools.jline.console.history.FileHistory
import scala.util.{Failure, Success, Try}

/**
  * Trifecta Shell (REPL)
  * @author lawrence.daniels@gmail.com
  */
object TrifectaShell {
  private val logger = LoggerFactory.getLogger(getClass)
  val VERSION = CoreModule.VERSION

  /**
    * Application entry point
    * @param args the given command line arguments
    */
  def main(args: Array[String]) {
    // use the ANSI console plugin to display the title line
    System.out.println(s"Trifecta v$VERSION")

    // load the configuration
    logger.info(s"Loading configuration file '${TxConfig.configFile}'...")
    val config = Try(TxConfig.load(TxConfig.configFile)) match {
      case Success(cfg) => cfg
      case Failure(e) =>
        val cfg = TxConfig.defaultConfig
        if (!TxConfig.configFile.exists()) {
          logger.info(s"Creating default configuration file (${TxConfig.configFile.getAbsolutePath})...")
          cfg.save(TxConfig.configFile)
        }
        cfg
    }

    // startup the Kafka Sandbox?
    if (args.contains("--kafka-sandbox")) {
      logger.info("Starting Kafka Sandbox...")
      val kafkaSandbox = KafkaSandbox()
      config.zooKeeperConnect = kafkaSandbox.getConnectString
      Thread.sleep(3000)
    }

    // initialize the shell
    val console = new TrifectaConsole(new TxRuntimeContext(config))

    // if arguments were not passed, stop.
    args.filterNot(_.startsWith("--")).toList match {
      case Nil =>
        console.shell()
      case params =>
        val line = params mkString " "
        console.execute(line)
    }
  }

  /**
    * Trifecta Console Shell Application
    * @author lawrence.daniels@gmail.com
    */
  class TrifectaConsole(rt: TxRuntimeContext) {
    private val config = rt.config

    // redirect standard output
    val out: PrintStream = config.out
    val err: PrintStream = config.err

    /**
      * Executes the given command line expression
      * @param line the given command line expression
      */
    def execute(line: String) {
      rt.interpret(line) match {
        case Success(result) =>
          // if debug is enabled, display the object value and class name
          if (config.debugOn) showDebug(result)

          // handle the result
          rt.handleResult(result, line)
        case Failure(e: ConnectionLossException) =>
          err.println("Zookeeper connect loss error - use 'zconnect' to re-establish a connection")
        case Failure(e: IllegalArgumentException) =>
          if (rt.config.debugOn) e.printStackTrace()
          err.println(s"Syntax error: ${getErrorMessage(e)}")
        case Failure(e) =>
          if (rt.config.debugOn) e.printStackTrace()
          err.println(s"Runtime error: ${getErrorMessage(e)}")
      }
    }

    /**
      * Interactive shell
      */
    def shell() {
      // use the ANSI console plugin to display the title line
      out.println("Type 'help' (or ?) to see the list of available commands")

      if (rt.config.autoSwitching) {
        out.println("Module Auto-Switching is On")
      }

      // define the console reader
      val consoleReader = new ConsoleReader()
      consoleReader.setExpandEvents(false)
      val history = new FileHistory(TxConfig.historyFile)
      consoleReader.setHistory(history)

      do {
        // display the prompt, and get the next line of input
        val module = rt.moduleManager.activeModule getOrElse rt.moduleManager.modules.head

        // read a line from the console
        Try {
          Option(consoleReader.readLine("%s:%s> ".format(module.moduleLabel, module.prompt))) map (_.trim) foreach { line =>
            if (line.nonEmpty) {
              rt.interpret(line) match {
                case Success(result) =>
                  // if debug is enabled, display the object value and class name
                  if (config.debugOn) showDebug(result)

                  // handle the result
                  rt.handleResult(result, line)
                case Failure(e: ConnectionLossException) =>
                  err.println("Zookeeper connect loss error - use 'zconnect' to re-establish a connection")
                case Failure(e: IllegalArgumentException) =>
                  if (rt.config.debugOn) e.printStackTrace()
                  err.println(s"Syntax error: ${getErrorMessage(e)}")
                case Failure(e) =>
                  if (rt.config.debugOn) e.printStackTrace()
                  err.println(s"Runtime error: ${getErrorMessage(e)}")
              }
            }
          }
        }
      } while (config.isAlive)

      // flush the console
      consoleReader.flush()
      consoleReader.setExpandEvents(false)

      // shutdown all resources
      rt.shutdown()
    }

    private def showDebug(result: Any): Unit = {
      result match {
        case Failure(e) => e.printStackTrace(out)
        case AsyncIO(task, counter) => showDebug(task.value)
        case f: Future[_] => showDebug(f.value)
        case Some(v) => showDebug(v)
        case Success(v) => showDebug(v)
        case v => out.println(s"result: $result ${Option(result) map (_.getClass.getName) getOrElse ""}")
      }
    }

    private def getErrorMessage(t: Throwable): String = {
      Option(t.getMessage) getOrElse t.toString
    }

  }

}
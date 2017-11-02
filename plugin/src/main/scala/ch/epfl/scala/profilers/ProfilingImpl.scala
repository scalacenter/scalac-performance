/*                                                                                                *\
**      _____            __         ______           __                                           **
**     / ___/_________ _/ /___ _   / ____/__  ____  / /____  _____                                **
**     \__ \/ ___/ __ `/ / __ `/  / /   / _ \/ __ \/ __/ _ \/ ___/    Scala Center                **
**    ___/ / /__/ /_/ / / /_/ /  / /___/ /__/ / / / /_/ /__/ /        https://scala.epfl.ch       **
**   /____/\___/\__,_/_/\__,_/   \____/\___/_/ /_/\__/\___/_/         (c) 2017-2018, LAMP/EPFL    **
**                                                                                                **
\*                                                                                                */

package ch.epfl.scala.profilers

import scala.tools.nsc.Global
import ch.epfl.scala.profilers.tools.Logger

import scala.reflect.internal.util.StatisticsStatics

final class ProfilingImpl[G <: Global](override val global: G, logger: Logger[G])
    extends ProfilingStats {
  import global._

  def registerProfilers(): Unit = {
    // Register our profiling macro plugin
    analyzer.addMacroPlugin(ProfilingMacroPlugin)
    analyzer.addAnalyzerPlugin(ProfilingAnalyzerPlugin)
  }

  /**
    * Represents the profiling information about expanded macros.
    *
    * Note that we could derive the value of expanded macros from the
    * number of instances of [[MacroInfo]] if it were not by the fact
    * that a macro can expand in the same position more than once. We
    * want to be able to report/analyse such cases on their own, so
    * we keep it as a paramater of this entity.
    */
  case class MacroInfo(expandedMacros: Int, expandedNodes: Int, expansionNanos: Long) {
    def +(other: MacroInfo): MacroInfo = {
      val totalExpanded = expandedMacros + other.expandedMacros
      val totalNodes = expandedNodes + other.expandedNodes
      val totalTime = expansionNanos + other.expansionNanos
      MacroInfo(totalExpanded, totalNodes, totalTime)
    }
  }

  object MacroInfo {
    final val Empty = MacroInfo(0, 0, 0L)
    implicit val macroInfoOrdering: Ordering[MacroInfo] = Ordering.by(_.expansionNanos)
    def aggregate(infos: Iterator[MacroInfo]): MacroInfo = {
      infos.foldLeft(MacroInfo.Empty)(_ + _)
    }
  }

  import scala.reflect.internal.util.SourceFile
  case class MacroProfiler(
      perCallSite: Map[Position, MacroInfo],
      perFile: Map[SourceFile, MacroInfo],
      inTotal: MacroInfo,
      repeatedExpansions: Map[Tree, Int]
  )

  def toMillis(nanos: Long): Long =
    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos)

  def groupPerFile[V](
      kvs: Map[Position, V]
  )(empty: V, aggregate: (V, V) => V): Map[SourceFile, V] = {
    kvs.groupBy(_._1.source).mapValues {
      case posInfos: Map[Position, V] => posInfos.valuesIterator.fold(empty)(aggregate)
    }
  }

  lazy val getMacroProfiler: MacroProfiler = {
    import ProfilingMacroPlugin.{macroInfos, repeatedTrees}
    val perCallSite = macroInfos.toMap
    val perFile = groupPerFile(perCallSite)(MacroInfo.Empty, _ + _)
      .mapValues(i => i.copy(expansionNanos = toMillis(i.expansionNanos)))
    val inTotal = MacroInfo.aggregate(perFile.valuesIterator)
    val repeated = repeatedTrees.toMap.valuesIterator
      .filter(_.count > 1)
      .map(v => v.original -> v.count)
      .toMap
    // perFile and inTotal are already converted to millis
    val callSiteNanos = perCallSite
      .mapValues(i => i.copy(expansionNanos = toMillis(i.expansionNanos)))
    MacroProfiler(callSiteNanos, perFile, inTotal, repeated)
  }

  case class ImplicitInfo(count: Int) {
    def +(other: ImplicitInfo): ImplicitInfo = ImplicitInfo(count + other.count)
  }

  object ImplicitInfo {
    final val Empty = ImplicitInfo(0)
    def aggregate(infos: Iterator[ImplicitInfo]): ImplicitInfo = infos.fold(Empty)(_ + _)
    implicit val infoOrdering: Ordering[ImplicitInfo] = Ordering.by(_.count)
  }

  case class ImplicitProfiler(
      perCallSite: Map[Position, ImplicitInfo],
      perFile: Map[SourceFile, ImplicitInfo],
      perType: Map[Type, ImplicitInfo],
      inTotal: ImplicitInfo
  )

  lazy val getImplicitProfiler: ImplicitProfiler = {
    val perCallSite = implicitSearchesByPos.toMap.mapValues(ImplicitInfo.apply)
    val perFile = groupPerFile[ImplicitInfo](perCallSite)(ImplicitInfo.Empty, _ + _)
    val perType = implicitSearchesByType.toMap.mapValues(ImplicitInfo.apply)
    val inTotal = ImplicitInfo.aggregate(perFile.valuesIterator)
    ImplicitProfiler(perCallSite, perFile, perType, inTotal)
  }

  object ProfilingAnalyzerPlugin extends global.analyzer.AnalyzerPlugin {
    override def pluginsNotifyImplicitSearch(search: global.analyzer.ImplicitSearch): Unit = {
      if (StatisticsStatics.areSomeColdStatsEnabled() && statistics.areStatisticsLocallyEnabled) {
        val targetType = search.pt
        val targetPos = search.pos
        val typeCounter = implicitSearchesByType.getOrElse(targetType, 0)
        implicitSearchesByType.update(targetType, typeCounter + 1)
        val posCounter = implicitSearchesByPos.getOrElse(targetPos, 0)
        implicitSearchesByPos.update(targetPos, posCounter + 1)
      }
    }
  }

  /**
    * The profiling macro plugin instruments the macro interface to check
    * certain behaviours. For now, the profiler takes care of:
    *
    * - Reporting the size of expanded trees.
    *
    * It would be useful in the future to report on the amount of expanded
    * trees that are and are not discarded.
    */
  object ProfilingMacroPlugin extends global.analyzer.MacroPlugin {
    type Typer = analyzer.Typer
    private def guessTreeSize(tree: Tree): Int =
      1 + tree.children.map(guessTreeSize).sum

    type RepeatedKey = (String, String)
    case class RepeatedValue(original: Tree, result: Tree, count: Int)
    private[ProfilingImpl] val repeatedTrees = perRunCaches.newMap[RepeatedKey, RepeatedValue]
    private[ProfilingImpl] val macroInfos = perRunCaches.newMap[Position, MacroInfo]
    private final val EmptyRepeatedValue = RepeatedValue(EmptyTree, EmptyTree, 0)

    import scala.tools.nsc.Mode
    override def pluginsMacroExpand(t: Typer, expandee: Tree, md: Mode, pt: Type): Option[Tree] = {
      object expander extends analyzer.DefMacroExpander(t, expandee, md, pt) {
        private var alreadyTracking: Boolean = false

        /**
          * Overrides the default method that expands all macros.
          *
          * We perform this because we need our own timer and access to the first timer snapshot
          * in order to obtain the expansion time for every expanded tree.
          */
        override def apply(desugared: Tree): Tree = {
          val shouldTrack = statistics.enabled && !alreadyTracking
          val start = if (shouldTrack) {
            alreadyTracking = true
            statistics.startTimer(preciseMacroTimer)
          } else null
          try super.apply(desugared)
          finally if (shouldTrack) {
            alreadyTracking = false
            updateExpansionTime(desugared, start)
          } else ()
        }

        def updateExpansionTime(desugared: Tree, start: statistics.TimerSnapshot): Unit = {
          statistics.stopTimer(preciseMacroTimer, start)
          val (nanos0, _) = start
          val timeNanos = (preciseMacroTimer.nanos - nanos0)
          val callSitePos = desugared.pos
          // Those that are not present failed to expand
          macroInfos.get(callSitePos).foreach { found =>
            val updatedInfo = found.copy(expansionNanos = timeNanos)
            macroInfos(callSitePos) = updatedInfo
          }
        }

        override def onFailure(expanded: Tree) = {
          statistics.incCounter(failedMacros)
          super.onFailure(expanded)
        }

        override def onDelayed(expanded: Tree) = {
          statistics.incCounter(delayedMacros)
          super.onDelayed(expanded)
        }

        override def onSuccess(expanded: Tree) = {
          val callSitePos = expandee.pos
          val printedExpandee = showRaw(expandee)
          val printedExpanded = showRaw(expanded)
          val key = (printedExpandee, printedExpanded)
          val currentValue = repeatedTrees.getOrElse(key, EmptyRepeatedValue)
          val newValue = RepeatedValue(expandee, expanded, currentValue.count + 1)
          repeatedTrees.put(key, newValue)
          val macroInfo = macroInfos.getOrElse(callSitePos, MacroInfo.Empty)
          val expandedMacros = macroInfo.expandedMacros + 1
          val treeSize = macroInfo.expandedNodes + guessTreeSize(expanded)
          // Use 0L for the timer because it will be filled in by the caller `apply`
          macroInfos.put(callSitePos, MacroInfo(expandedMacros, treeSize, 0L))
          super.onSuccess(expanded)
        }
      }
      Some(expander(expandee))
    }
  }
}

trait ProfilingStats {
  val global: Global
  import global.statistics._

  macroExpandCount.children.clear()
  final val preciseMacroTimer = newTimer("precise time in macroExpand")
  final val failedMacros = newSubCounter("  of which failed macros", macroExpandCount)
  final val delayedMacros = newSubCounter("  of which delayed macros", macroExpandCount)

  import scala.reflect.internal.util.Position
  final val implicitSearchesByType = global.perRunCaches.newMap[global.Type, Int]()
  final val implicitSearchesByPos = global.perRunCaches.newMap[Position, Int]()
}

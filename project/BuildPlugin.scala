/*                                                                                                *\
**      _____            __         ______           __                                           **
**     / ___/_________ _/ /___ _   / ____/__  ____  / /____  _____                                **
**     \__ \/ ___/ __ `/ / __ `/  / /   / _ \/ __ \/ __/ _ \/ ___/    Scala Center                **
**    ___/ / /__/ /_/ / / /_/ /  / /___/ /__/ / / / /_/ /__/ /        https://scala.epfl.ch       **
**   /____/\___/\__,_/_/\__,_/   \____/\___/_/ /_/\__/\___/_/         (c) 2017-2018, LAMP/EPFL    **
**                                                                                                **
\*                                                                                                */

package build

import sbt.{AutoPlugin, Def, Keys, PluginTrigger, Plugins}

object BuildPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = ch.epfl.scala.sbt.release.ReleaseEarlyPlugin
  val autoImport = BuildKeys

  override def globalSettings: Seq[Def.Setting[_]] =
    BuildImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    BuildImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    BuildImplementation.projectSettings
}

object BuildKeys {
  import sbt.{settingKey, taskKey, richFile, file, uri, toGroupID}
  import sbt.{RootProject, ProjectRef, Setting, Compile, BuildRef, Reference}
  final val enableStatistics =
    settingKey[Boolean]("Enable performance debugging if true.")
  final val optionsForSourceCompilerPlugin =
    taskKey[Seq[String]]("Generate scalac options for source compiler plugin")
  final val showScalaInstances = taskKey[Unit]("Show versions of all integration tests")

  // Refer to setting via reference because there is no dependency to the scalac build here.
  final val scalacVersionSuffix = sbt.SettingKey[String]("baseVersionSuffix")

  // Use absolute paths so that references work even though the `ThisBuild` changes
  final val AbsolutePath = file(".").getCanonicalFile.getAbsolutePath
  final val HomeBuild = BuildRef(RootProject(file(AbsolutePath)).build)

  // Source dependency is a submodule that we modify
  final val Scalac = RootProject(file(s"$AbsolutePath/scalac"))
  final val ScalacBuild = BuildRef(Scalac.build)
  final val ScalacCompiler = ProjectRef(Scalac.build, "compiler")
  final val ScalacLibrary = ProjectRef(Scalac.build, "library")
  final val ScalacReflect = ProjectRef(Scalac.build, "reflect")
  final val AllScalacProjects = List(ScalacCompiler, ScalacLibrary, ScalacReflect)

  final val VscodeScala = RootProject(file(s"$AbsolutePath/vscode-scala"))
  final val VscodeImplementation = ProjectRef(VscodeScala.build, "ensime-lsp")

  // Source dependencies from git are cached by sbt
  val Circe = RootProject(
    uri("git://github.com/jvican/circe.git#74daecae981ff5d7521824fea5304f9cb52dbac9")
  )
  val Monocle = RootProject(
    uri("git://github.com/jvican/Monocle.git#93e72ed4db8217a872ab8770fbf3cba504489596")
  )
  val Scalatest = RootProject(
    uri("git://github.com/jvican/scalatest.git#2bc97995612c467e4248a33b2ad0025c003a0fcb")
  )

  val CirceTests = ProjectRef(Circe.build, "tests")
  val MonocleExample = ProjectRef(Monocle.build, "example")
  val MonocleTests = ProjectRef(Monocle.build, "testJVM")
  val ScalatestCore = ProjectRef(Scalatest.build, "scalatest")
  val ScalatestTests = ProjectRef(Scalatest.build, "scalatest-test")
  val AllIntegrationProjects =
    List(CirceTests, MonocleExample, MonocleTests, ScalatestCore, ScalatestTests)

  // Assumes that the previous scala version is the last bincompat version
  final val ScalacVersion = Keys.version in BuildKeys.ScalacCompiler
  final val PreviousScalaVersion = Keys.scalaVersion in BuildKeys.ScalacCompiler

  final val testDependencies = List(
    "junit" % "junit" % "4.12" % "test",
    "com.novocode" % "junit-interface" % "0.11" % "test"
  )

  /** Write all the compile-time dependencies of the compiler plugin to a file,
    * in order to read it from the created Toolbox to run the neg tests. */
  lazy val generateToolboxClasspath = Def.task {
    val scalaBinVersion = (Keys.scalaBinaryVersion in Compile).value
    val targetDir = (Keys.target in Compile).value
    val compiledClassesDir = targetDir / s"scala-$scalaBinVersion/classes"
    val testClassesDir = targetDir / s"scala-$scalaBinVersion/test-classes"
    val libraryJar = Keys.scalaInstance.value.libraryJar.getAbsolutePath
    val deps = (Keys.libraryDependencies in Compile).value.mkString(":")
    val classpath = s"$compiledClassesDir:$testClassesDir:$libraryJar:$deps"
    val resourceDir = (Keys.resourceManaged in Compile).value
    val toolboxTestClasspath = resourceDir / "toolbox.classpath"
    sbt.IO.write(toolboxTestClasspath, classpath)
    List(toolboxTestClasspath.getAbsoluteFile)
  }

  /**
    * Sbt does not like overrides of setting values that happen in ThisBuild,
    * nor in other project settings like integrations'. No. Sbt is exigent and
    * always asks you to give your best.
    *
    * Why so much code for such a simple idea? Well, `Project.extract` does force
    * the execution and initialization of settings, so as `onLoad` is a setting
    * it causes a recursive call to itself, yay!
    *
    * So, in short, solution: use an attribute in the state to short-circuit the
    * recursive invocation.
    *
    * Notes to the future reader: the bug that prompted this solution is weird
    * I can indeed override lots of settings via project refs, but when it comes
    * to overriding a setting **in a project** (that has been generated via
    * sbt-cross-project), it does not work. On top of this, this wouldn't happen
    * if monocle defined the scala versions at the build level (it instead does it
    * at the project level, which is bad practice). So, finding a repro for this
    * is going to be fun.
    */
  final val hijacked = sbt.AttributeKey[Boolean]("The hijacked sexy option.")

  ////////////////////////////////////////////////////////////////////////////////

  def inProject(ref: Reference)(ss: Seq[Setting[_]]): Seq[Setting[_]] =
    sbt.inScope(sbt.ThisScope.in(project = ref))(ss)

  def inProjectRefs(refs: Seq[Reference])(ss: Setting[_]*): Seq[Setting[_]] =
    refs.flatMap(inProject(_)(ss))

  def inScalacProjects(ss: Setting[_]*): Seq[Setting[_]] =
    inProjectRefs(AllScalacProjects)(ss: _*)

  def inCompileAndTest(ss: Setting[_]*): Seq[Setting[_]] =
    Seq(sbt.Compile, sbt.Test).flatMap(sbt.inConfig(_)(ss))

  import sbt.complete.Parser
  import sbt.complete.DefaultParsers._
  val CirceKeyword = " circe"
  val CirceParser: Parser[String] = CirceKeyword
  val MonocleKeyword = " monocle"
  val IntegrationKeyword = " integration"
  val ScalatestKeyword = " scalatest"
  val AllKeywords = List(CirceKeyword, MonocleKeyword, IntegrationKeyword, ScalatestKeyword)
  val AllParsers = CirceParser | MonocleKeyword | IntegrationKeyword | ScalatestKeyword
  private val keywordsParser = AllParsers.+.examples(AllKeywords: _*)
  val keywordsSetting: Def.Initialize[sbt.State => Parser[Seq[String]]] =
    Def.setting((state: sbt.State) => keywordsParser)
}

object BuildImplementation {
  import sbt.{url, file, richFile, State, Logger}
  import sbt.{ScmInfo, Developer, Resolver, ThisBuild, Watched, Compile, Test}

  // This should be added to upstream sbt.
  def GitHub(org: String, project: String): java.net.URL =
    url(s"https://github.com/$org/$project")
  def GitHubDev(handle: String, fullName: String, email: String) =
    Developer(handle, fullName, email, url(s"https://github.com/$handle"))

  import com.typesafe.sbt.SbtPgp.autoImport.PgpKeys
  import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin.{autoImport => ReleaseEarlyKeys}

  private final val ThisRepo = GitHub("scalacenter", "scalac-profiling")
  final val publishSettings: Seq[Def.Setting[_]] = Seq(
    Keys.startYear := Some(2017),
    Keys.autoAPIMappings := true,
    Keys.publishMavenStyle := true,
    Keys.homepage := Some(ThisRepo),
    Keys.publishArtifact in Test := false,
    Keys.licenses := Seq("BSD" -> url("http://opensource.org/licenses/BSD-3-Clause")),
    Keys.developers := List(GitHubDev("jvican", "Jorge Vicente Cantero", "jorge@vican.me")),
    PgpKeys.pgpPublicRing := file("/drone/.gnupg/pubring.asc"),
    PgpKeys.pgpSecretRing := file("/drone/.gnupg/secring.asc"),
    ReleaseEarlyKeys.releaseEarlyWith := ReleaseEarlyKeys.SonatypePublisher
  )

  // Paranoid level: removes doc generation by all means
  final val scalacSettings: Seq[Def.Setting[_]] = BuildKeys.inScalacProjects(
    Keys.aggregate in Keys.doc := false,
    Keys.sources in Compile in Keys.doc := Seq.empty,
    Keys.scalacOptions in Compile in Keys.doc := Seq.empty,
    Keys.publishArtifact in Compile in Keys.packageDoc := false,
    // Use snapshot only for local development plz.
    // If placed in global settings, it's not applied. Sbt bug?
    BuildKeys.scalacVersionSuffix in BuildKeys.Scalac := "bin-stats-SNAPSHOT"
  )

  object BuildDefaults {
    final val showScalaInstances: Def.Initialize[sbt.Task[Unit]] = Def.task {
      val logger = Keys.streams.value.log
      logger.info((Keys.name in Test in BuildKeys.CirceTests).value)
      logger.info((Keys.scalaInstance in Test in BuildKeys.CirceTests).value.toString)
      logger.info((Keys.name in Test in BuildKeys.MonocleTests).value)
      logger.info((Keys.scalaInstance in Test in BuildKeys.MonocleTests).value.toString)
      logger.info((Keys.name in Test in BuildKeys.MonocleExample).value)
      logger.info((Keys.scalaInstance in Test in BuildKeys.MonocleExample).value.toString)
      ()
    }

    type Hook = Def.Initialize[State => State]

    private final val UnknownHash = "UNKNOWN"
    final val publishForkScalac: Hook = Def.setting { (state: State) =>
      import sbt.IO
      import com.typesafe.sbt.git.JGit
      // Only publish scalac if file doesn't exist
      val baseDirectory = (Keys.baseDirectory in sbt.ThisBuild).value
      val scalacHashFile = baseDirectory./(".scalac-hash")
      val repository = JGit(baseDirectory./("scalac"))
      // If sha cannot be fetched, always force publishing of fork.
      val currentHash = repository.headCommitSha.getOrElse(UnknownHash)
      if (!repository.hasUncommittedChanges &&
        scalacHashFile.exists() &&
        currentHash == IO.read(scalacHashFile)) {
        state
      } else {
        val logger = Keys.sLog.value
        val extracted = sbt.Project.extract(state)
        val buildData = extracted.structure.data
        val maybeVersion = BuildKeys.ScalacVersion.get(buildData)
        maybeVersion match {
          case Some(version) =>
            (Keys.scalaVersion in ThisBuild).get(buildData) match {
              case Some(scalaVersion) if scalaVersion == version =>
                val newState = publishCustomScalaFork(state, version, logger)
                if (currentHash != UnknownHash)
                  IO.write(scalacHashFile, currentHash)
                newState
              case _ => state // Do nothing
            }
          case None => state // Do nothing
        }
      }
    }

    import sbt.ModuleID

    /**
      * Removes scala version from those modules that use full cross version and injects
      * the manual Scala version in the library dependency name assuming that the library
      * will not use any binary incompatible change in the compiler sources (or assuming
      * that there is none, which is even better!).
      */
    def trickLibraryDependency(dependency: ModuleID, validVersion: String): ModuleID = {
      dependency.crossVersion match {
        case fullVersion: sbt.CrossVersion.Full =>
          val manualNameWithScala = s"${dependency.name}_$validVersion"
          dependency.copy(name = manualNameWithScala).copy(crossVersion = sbt.CrossVersion.Disabled)
        case _ => dependency
      }
    }

    final val PluginProject = sbt.LocalProject("plugin")
    final val hijackScalaVersions: Hook = Def.setting { (state: State) =>
      if (state.get(BuildKeys.hijacked).getOrElse(false)) state.remove(BuildKeys.hijacked)
      else {
        val hijackedState = state.put(BuildKeys.hijacked, true)
        val extracted = sbt.Project.extract(hijackedState)
        val forkedScalaVersion = (Keys.scalaVersion in Test in PluginProject).value
        val globalSettings = List(
          Keys.onLoadMessage in sbt.Global := s"Preparing the build to use Scalac $forkedScalaVersion."
        )
        val projectSettings = BuildKeys.inProjectRefs(BuildKeys.AllIntegrationProjects)(
          Keys.scalaVersion := forkedScalaVersion,
          Keys.scalacOptions ++= {
            val projectBuild = Keys.thisProjectRef.value.build
            val workingDir = Keys.buildStructure.value.units(projectBuild).localBase.getAbsolutePath
            val sourceRoot = s"-P:scalac-profiling:sourceroot:$workingDir"
            val pluginOpts = (BuildKeys.optionsForSourceCompilerPlugin in PluginProject).value
            sourceRoot +: pluginOpts
          },
          Keys.libraryDependencies ~= { previousDependencies =>
            // Assumes that all of these projects are on the same bincompat version (2.12.x)
            val validScalaVersion = BuildKeys.PreviousScalaVersion.value
            previousDependencies.map(dep => trickLibraryDependency(dep, validScalaVersion))
          }
        )
        // NOTE: This is done because sbt does not handle session settings correctly. Should be reported upstream.
        val currentSession = sbt.Project.session(state)
        val currentProject = currentSession.current
        val currentSessionSettings =
          currentSession.append.get(currentProject).toList.flatten.map(_._1)
        val allSessionSettings = currentSessionSettings ++ currentSession.rawAppend
        extracted.append(globalSettings ++ projectSettings ++ allSessionSettings, hijackedState)
      }
    }

    final val customOnLoad: Hook =
      Def.setting(publishForkScalac.value andThen hijackScalaVersions.value)
  }

  final val globalSettings: Seq[Def.Setting[_]] = Seq(
    Keys.testOptions in Test += sbt.Tests.Argument("-oD"),
    Keys.onLoad := (Keys.onLoad in sbt.Global).value andThen (BuildDefaults.customOnLoad.value),
    Keys.onLoadMessage := Header.intro
  )

  final val commandAliases: Seq[Def.Setting[sbt.State => sbt.State]] = {
    val scalacRef = sbt.Reference.display(BuildKeys.ScalacBuild)
    val scalac = sbt.addCommandAlias("scalac", s"project ${scalacRef}")
    val homeRef = sbt.Reference.display(BuildKeys.HomeBuild)
    val home = sbt.addCommandAlias("home", s"project ${homeRef}")
    scalac ++ home
  }

  private final val ScalaVersions = Seq("2.11.11", "2.12.3")
  final val buildSettings: Seq[Def.Setting[_]] = Seq(
    Keys.organization := "ch.epfl.scala",
    Keys.resolvers += Resolver.jcenterRepo,
    Keys.updateOptions := Keys.updateOptions.value.withCachedResolution(true),
    Keys.scalaVersion := BuildKeys.ScalacVersion.value,
    Keys.crossScalaVersions := ScalaVersions ++ List(BuildKeys.ScalacVersion.value),
    Keys.triggeredMessage := Watched.clearWhenTriggered,
    BuildKeys.enableStatistics := sys.env.get("CI").isDefined,
    BuildKeys.showScalaInstances := BuildDefaults.showScalaInstances.value,
    Keys.publishArtifact in Compile in Keys.packageDoc := false
  ) ++ publishSettings ++ commandAliases

  final val projectSettings: Seq[Def.Setting[_]] = Seq(
    Keys.scalacOptions in Compile := reasonableCompileOptions,
    // Necessary because the scalac version has to be always SNAPSHOT to avoid caching issues
    // Scope here is wrong -- we put it here temporarily until this is fixed upstream
    ReleaseEarlyKeys.releaseEarlyBypassSnapshotCheck := true
  ) ++ scalacSettings

  final val reasonableCompileOptions = (
    "-deprecation" :: "-encoding" :: "UTF-8" :: "-feature" :: "-language:existentials" ::
      "-language:higherKinds" :: "-language:implicitConversions" :: "-unchecked" ::
      "-Yno-adapted-args" :: "-Ywarn-numeric-widen" :: "-Xfuture" :: "-Xlint" :: Nil
  )

  private def publishCustomScalaFork(state0: State, version: String, logger: Logger): State = {
    import sbt.{Project, Value, Inc, Incomplete}
    logger.warn(s"Publishing Scala version $version from the fork...")
    // Bah, reuse the same state for everything.
    val publishing = Project
      .runTask(Keys.publishLocal in BuildKeys.ScalacLibrary, state0)
      .flatMap(_ => Project.runTask(Keys.publishLocal in BuildKeys.ScalacReflect, state0))
      .flatMap(_ => Project.runTask(Keys.publishLocal in BuildKeys.ScalacCompiler, state0))
    publishing match {
      case None => sys.error(s"Key for publishing is not defined?")
      case Some((newState, Value(v))) => newState
      case Some((newState, Inc(inc))) =>
        sys.error(s"Got error when publishing the Scala fork: $inc")
    }
  }
}

object Header {
  val intro: String =
    """      _____            __         ______           __
      |     / ___/_________ _/ /___ _   / ____/__  ____  / /____  _____
      |     \__ \/ ___/ __ `/ / __ `/  / /   / _ \/ __ \/ __/ _ \/ ___/
      |    ___/ / /__/ /_/ / / /_/ /  / /___/ /__/ / / / /_/ /__/ /
      |   /____/\___/\__,_/_/\__,_/   \____/\___/_/ /_/\__/\___/_/
      |
      |   ***********************************************************
      |   ***       Welcome to the build of scalac-profiling      ***
      |   *** An effort funded by the Scala Center Advisory Board ***
      |   ***********************************************************
    """.stripMargin
}

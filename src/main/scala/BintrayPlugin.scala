package bintray

import bintry.Attr
import sbt.{ AutoPlugin, Credentials, Global, Path, Resolver, Setting, Task, Tags, ThisBuild }
import sbt.Classpaths.publishTask
import sbt.Def.{ Initialize, setting, task, taskDyn }
import sbt.Keys._
import sbt._

object BintrayPlugin extends AutoPlugin {
  import BintrayKeys._
  import InternalBintrayKeys._

  override def requires = sbt.plugins.JvmPlugin
  override def trigger = allRequirements
  
  override def globalSettings: Seq[Setting[_]] = globalPublishSettings
  override def buildSettings: Seq[Setting[_]] = buildPublishSettings
  override def projectSettings: Seq[Setting[_]] = bintraySettings

  object autoImport extends BintrayKeys {
  }

  lazy val Git = Tags.Tag("git")

  def bintraySettings: Seq[Setting[_]] =
    bintrayCommonSettings ++ bintrayPublishSettings ++ bintrayQuerySettings

  def bintrayCommonSettings: Seq[Setting[_]] = Seq(
    bintrayChangeCredentials := {
      Bintray.changeCredentials(bintrayCredentialsFile.value)
    },
    bintrayWhoami := {
      Bintray.whoami(bintrayCredentialsFile.value, streams.value.log)
    }
  )

  def bintrayQuerySettings: Seq[Setting[_]] = Seq(
    bintrayPackageVersions := packageVersionsTask.value
  )

  def globalPublishSettings: Seq[Setting[_]] = Seq(
    bintrayCredentialsFile in Global := Path.userHome / ".bintray" / ".credentials",
    concurrentRestrictions in Global += Tags.exclusive(Git)
  )

  def buildPublishSettings: Seq[Setting[_]] = Seq(
    bintrayOrganization in ThisBuild := None,
    bintrayVcsUrl in ThisBuild := vcsUrlTask.value,
    bintrayReleaseOnPublish in ThisBuild := true
  )

  def bintrayPublishSettings: Seq[Setting[_]] = bintrayCommonSettings ++ Seq(
    bintrayPackage := moduleName.value,
    bintrayRepo := Bintray.cachedRepo(bintrayEnsureCredentials.value,
      bintrayOrganization.value,
      bintrayRepository.value),
    // todo: don't force this to be sbt-plugin-releases
    bintrayRepository := {
      if (sbtPlugin.value) Bintray.defaultSbtPluginRepository
      else Bintray.defaultMavenRepository
    },
    publishMavenStyle := {
      if (sbtPlugin.value) false else publishMavenStyle.value
    },
    bintrayPackageLabels := Nil,
    description in bintray := description.value,
    // note: publishTo may not have dependencies. therefore, we can not rely well on inline overrides
    // for inline credentials resolution we recommend defining bintrayCredentials _before_ mixing in the defaults
    // perhaps we should try overriding something in the publishConfig setting -- https://github.com/sbt/sbt-pgp/blob/master/pgp-plugin/src/main/scala/com/typesafe/sbt/pgp/PgpSettings.scala#L124-L131
    publishTo in bintray := publishToBintray.value,
    resolvers in bintray := {
      Bintray.buildResolvers(bintrayCredentialsFile.value,
        bintrayOrganization.value,
        bintrayRepository.value)
    },
    credentials in bintray := {
      Credentials(bintrayCredentialsFile.value) :: Nil
    },
    bintrayPackageAttributes := {
      if (sbtPlugin.value) Map(AttrNames.sbtPlugin -> Seq(Attr.Boolean(sbtPlugin.value)))
      else Map.empty
    },
    bintrayVersionAttributes := {
      val scalaVersions = crossScalaVersions.value
      val sv = Map(AttrNames.scalas -> scalaVersions.map(Attr.Version(_)))
      if (sbtPlugin.value) sv ++ Map(AttrNames.sbtVersion-> Seq(Attr.Version(sbtVersion.value)))
      else sv
    },
    bintrayOmitLicense := {
      if (sbtPlugin.value) sbtPlugin.value
      else false
    },
    bintrayEnsureLicenses := {
      Bintray.ensureLicenses(licenses.value, bintrayOmitLicense.value)
    },
    bintrayEnsureCredentials := {
      Bintray.ensuredCredentials(bintrayCredentialsFile.value).get
    },
    bintrayEnsureBintrayPackageExists := ensurePackageTask.value,
    bintrayUnpublish := {
      val e1 = bintrayEnsureBintrayPackageExists
      val e2 = bintrayEnsureLicenses
      val repo = bintrayRepo.value
      repo.unpublish(bintrayPackage.value, version.value, streams.value.log)
    },
    bintrayRemoteSign := {
      val repo = bintrayRepo.value
      repo.remoteSign(bintrayPackage.value, version.value, streams.value.log)
    },
    bintraySyncMavenCentral := {
      val repo = bintrayRepo.value
      repo.syncMavenCentral(bintrayPackage.value, version.value, credentials.value, streams.value.log)
    },
    bintrayRelease := {
      val _ = publishVersionAttributesTask.value
      val repo = bintrayRepo.value
      repo.release(bintrayPackage.value, version.value, streams.value.log)
    }
  ) ++ Seq(
    resolvers ++= (resolvers in bintray).value,
    credentials ++= (credentials in bintray).value,
    publishTo := (publishTo in bintray).value,
    publish := dynamicallyPublish.value
  )

  private def vcsUrlTask: Initialize[Task[Option[String]]] =
    task {
      Bintray.resolveVcsUrl.recover { case _ => None }.get
    } tag(Git)

  // uses taskDyn because it can return one of two potential tasks
  // as its result, each with their own dependencies
  // see also: http://www.scala-sbt.org/0.13/docs/Tasks.html#Dynamic+Computations+with 
  private def dynamicallyPublish: Initialize[Task[Unit]] =
    taskDyn {
      (if (bintrayReleaseOnPublish.value) bintrayRelease else warnToRelease).dependsOn(publishTask(publishConfiguration, deliver))
    } dependsOn(bintrayEnsureBintrayPackageExists, bintrayEnsureLicenses)

  private def warnToRelease: Initialize[Task[Unit]] =
    task {
      val log = streams.value.log
      log.warn("You must run bintrayRelease once all artifacts are staged.")
    }

  private def publishVersionAttributesTask: Initialize[Task[Unit]] =
    task {
      val repo = bintrayRepo.value
      repo.publishVersionAttributes(
        bintrayPackage.value,
        version.value,
        bintrayVersionAttributes.value)
    }

  private def ensurePackageTask: Initialize[Task[Unit]] =
    task {
      val vcs = bintrayVcsUrl.value.getOrElse {
        sys.error("""bintrayVcsUrl not defined. assign this with bintrayVcsUrl := Some("git@github.com:you/your-repo.git")""")
      }
      val repo = bintrayRepo.value
      repo.ensurePackage(bintrayPackage.value,
        bintrayPackageAttributes.value,
        (description in bintray).value,
        vcs,
        licenses.value,
        bintrayPackageLabels.value,
        streams.value.log)
    }

  /** set a user-specific bintray endpoint for sbt's `publishTo` setting.*/
  private def publishToBintray: Initialize[Option[Resolver]] =
    setting {
      val credsFile = bintrayCredentialsFile.value
      val btyOrg = bintrayOrganization.value
      val repoName = bintrayRepository.value
      // ensure that we have credentials to build a resolver that can publish to bintray
      Bintray.withRepo(credsFile, btyOrg, repoName, prompt = false) { repo =>
        repo.buildPublishResolver(bintrayPackage.value,
          version.value,
          publishMavenStyle.value,
          sbtPlugin.value,
          bintrayReleaseOnPublish.value)
      }
    }

  /** Lists versions of bintray packages corresponding to the current project */
  private def packageVersionsTask: Initialize[Task[Seq[String]]] =
    task {
      val credsFile = bintrayCredentialsFile.value
      val btyOrg = bintrayOrganization.value
      val repoName = bintrayRepository.value
      (Bintray.withRepo(credsFile, btyOrg, repoName) { repo =>
        repo.packageVersions(bintrayPackage.value, streams.value.log)
      }).getOrElse(Nil)
    }
}

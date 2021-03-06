package com.liyaos.forklift.slick

import java.io.File
import java.io.BufferedWriter
import java.io.FileWriter
import com.liyaos.forklift.core.MigrationsConfig
import com.liyaos.forklift.core.MigrationFilesHandler
import com.liyaos.forklift.core.RescueCommands
import com.liyaos.forklift.core.RescueCommandLineTool
import com.liyaos.forklift.core.MigrationCommands
import com.liyaos.forklift.core.MigrationCommandLineTool

trait SlickMigrationFilesHandler extends MigrationFilesHandler[Int] {
  def nameIsId(name: String) =
    name forall Character.isDigit

  def nameToId(name: String): Int =
    name.toInt

  def idShouldBeHandled(id: String, appliedIds: Seq[Int]) =
    if (appliedIds.isEmpty) id.toInt == 1
    else id.toInt <= appliedIds.max + 1

  def nextId = {
    val unhandled = new File(unhandledLoc)
    val ids = for {
      file <- unhandled.listFiles
      name <- getId(file.getName)
      if nameIsId(name)
    } yield nameToId(name)
    if (!ids.isEmpty) ids.max + 1 else 1
  }
}

trait SlickRescueCommands extends RescueCommands[Int]
    with SlickMigrationFilesHandler {
  this: SlickCodegen =>

  private def deleteRecursively(f: File) {
    if (f.isDirectory) {
      for {
        files <- Option(f.listFiles)
        file <- files
      } deleteRecursively(file)
    }
    f.delete
  }

  override def rescueCommand {
    super.rescueCommand
    deleteRecursively(new File(generatedDir))
  }
}

trait SlickRescueCommandLineTool extends RescueCommandLineTool[Int] {
  this: SlickRescueCommands =>
}

trait SlickMigrationCommands extends MigrationCommands[Int, slick.dbio.DBIO[Unit]]
    with SlickMigrationFilesHandler {
  this: SlickMigrationManager with SlickCodegen =>

  override def applyOps: Seq[() => Unit] = List(
    () => applyOp, () => codegenOp)

  override def statusOp {
    val mf = migrationFiles(alreadyAppliedIds)
    if (!mf.isEmpty) {
      println("you still have unhandled migrations")
      println("use mg update to fetch these migrations")
    } else {
      val ny = notYetAppliedMigrations
      if( ny.size == 0 ) {
        println("your database is up-to-date")
      } else {
        println("your database is outdated, not yet applied migrations: "+notYetAppliedMigrations.map(_.id).mkString(", "))
      }
    }
  }

  override def statusCommand {
    try {
      super.statusCommand
    } finally {
      db.close()
    }
  }

  override def previewOp {
    println("-" * 80)
    println("NOT YET APPLIED MIGRATIONS PREVIEW:")
    println("")
    notYetAppliedMigrations.map { migration =>
      migration match{
        case m: SqlMigrationInterface[_] =>
          println( migration.id + " SqlMigration:")
          println( "\t" + m.queries.map(_.getDumpInfo.mainInfo).mkString("\n\t") )
        case m: DBIOMigration[_] =>
          println( migration.id + " DBIOMigration:")
          println( "\t" + m.code )
      }
      println("")
    }
    println("-" * 80)
  }

  override def previewCommand {
    try {
      super.previewCommand
    } finally {
      db.close()
    }
  }

  override def applyOp {
    val ids = notYetAppliedMigrations.map(_.id)
    println("applying migrations: " + ids.mkString(", "))
    up()
  }

  override def applyCommand {
    try {
      super.applyCommand
    } finally {
      db.close()
    }
  }

  override def migrateCommand(options: Seq[String]) {
    try {
      super.migrateCommand(options)
    } finally {
      db.close()
    }
  }

  override def initOp {
    super.initOp
    init
  }

  override def initCommand {
    try {
      super.initCommand
    } finally {
      db.close()
    }
  }

  override def resetOp {
    super.resetOp
    reset
    remove()
  }

  override def resetCommand {
    try {
      super.resetCommand
    } finally {
      db.close()
    }
  }

  override def updateCommand {
    try {
      super.updateCommand
    } finally {
      db.close()
    }
  }

//  def dbdumpCommand {
//    import scala.slick.driver.H2Driver.simple._
//    import Database.dynamicSession
//    import scala.slick.jdbc.StaticQuery._
//    db.withDynSession{
//      println( queryNA[String]("SCRIPT").list.mkString("\n") )
//    }
//  }

  object MigrationType extends Enumeration {
    type MigrationType = Value
    val SQL, DBIO = Value
  }
  import MigrationType._

  def addMigrationOp(tpe: MigrationType, version: Int) {
    val migrationObject = config.getString("migrations.migration_object")
    val driverName = dbConfig.driverName
    val content = tpe match {
      case SQL => s"""import ${driverName}.api._
import com.liyaos.forklift.slick.SqlMigration

object M${version} {
  ${migrationObject}.migrations = ${migrationObject}.migrations :+ SqlMigration(${version})(List(
    sqlu"" // your sql code goes here
  ))
}
"""
      case DBIO =>
        val imports =
          if (version > 1)
            s"""import datamodel.v${version - 1}.schema.tables.Users
import datamodel.v${version - 1}.schema.tables.UsersRow"""
          else ""
        s"""import ${driverName}.api._
import com.liyaos.forklift.slick.DBIOMigration
${imports}

object M${version} {
  ${migrationObject}.migrations = ${migrationObject}.migrations :+ DBIOMigration(${version})(
    DBIO.seq(
      // write your dbio actions here
    ))
}
"""
    }
    val file = new File(unhandledLoc + "/" + version + ".scala")
    if (!file.exists) file.createNewFile()
    val bw = new BufferedWriter(new FileWriter(file.getAbsoluteFile()))
    bw.write(content)
    bw.close()
  }

  def addMigrationCommand(options: Seq[String]) {
    try {
      val tpe = options(0).toLowerCase match {
        case s if s == "sql" || s == "s" => SQL
        case d if d == "dbio" || d == "d" => DBIO
      }
      addMigrationOp(tpe, nextId)
    } catch {
      case e: Throwable =>
        println("you must enter a proper parameter!")
    } finally {
      // no need to close db since it's not initialized in this command
    }
  }

  def codegenOp {
    genCode(this)
  }

  def codegenCommand {
    try {
      codegenOp
    } finally {
      db.close()
    }
  }
}


trait SlickMigrationCommandLineTool
    extends MigrationCommandLineTool[Int, slick.dbio.DBIO[Unit]] {
  this: SlickMigrationCommands =>

  override def execCommands(args: List[String]) = args match {
    case "codegen" :: Nil => codegenCommand
    case "new" :: tpe => addMigrationCommand(tpe)
    case _ => super.execCommands(args)
  }

  override def help = super.help + """
  codegen   generate data model code (table objects, case classes) from the
            database schema

  new       create a new migration file. please specify migration type with
            "s" (sql) or "d" (dbio)
"""
}

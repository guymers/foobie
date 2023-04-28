package zoobie.test

import cats.syntax.apply.*
import cats.syntax.show.*
import doobie.FC
import doobie.free.connection.ConnectionIO
import doobie.util.Colors
import zio.ZIO
import zio.test.Assertion
import zio.test.TestResult
import zoobie.DatabaseError
import zoobie.Transactor

object Checker {
  import doobie.util.testing.*

  def check[A: Analyzable](a: A): ZIO[Transactor, DatabaseError, TestResult] = {
    checkWith(FC.unit)(a)
  }

  def checkWith[A: Analyzable](conn: ConnectionIO[Unit])(a: A): ZIO[Transactor, DatabaseError, TestResult] = for {
    transactor <- ZIO.service[Transactor]
    result <- transactor.run(checkConnIOWith(conn)(a))
  } yield result

  def checkConnIO[A: Analyzable](a: A): ConnectionIO[TestResult] = {
    checkConnIOWith(FC.unit)(a)
  }

  def checkConnIOWith[A: Analyzable](conn: ConnectionIO[Unit])(a: A): ConnectionIO[TestResult] = {
    val args = Analyzable.unpack(a)
    (conn *> analyze(args)).map { report =>
      val msg = formatReport(args, report, Colors.Ansi).padLeft("  ").toString
      zio.test.assert(report.succeeded)(Assertion.isTrue ?? show"\n$msg\n")
    }
  }
}

// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import cats.syntax.apply.*
import doobie.syntax.string.*
import doobie.util.Read
import zio.ZIO
import zio.test.assertTrue
import zoobie.Transactor

object ManyRowsSuite extends PostgresDatabaseSpec {

  implicit val readTuple: Read[(String, String)] = (Read[String], Read[String]).tupled

  override val spec = suite("ManyRows")(
    test("select should take consistent memory") {
      for {
        transactor <- ZIO.service[Transactor]
        q = fr"SELECT a.name, b.name FROM city a, city b".query[(String, String)]
        count <- transactor.streamSingleConnection(q.stream).runCount
      } yield {
        assertTrue(count == 16_638_241)
      }
    },
  )

}

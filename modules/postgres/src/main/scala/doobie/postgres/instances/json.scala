// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.instances

import cats.data.NonEmptyList
import doobie.util.Get
import doobie.util.Put
import org.postgresql.util.PGobject
import doobie.util.typename.TypeName

object json {

  def jsonGetFromString[A: TypeName](f: String => Either[String, A]): Get[A] =
    Get.Advanced.other[PGobject](
      NonEmptyList.of("json"),
    ).temap { obj =>
      f(obj.getValue)
    }

  def jsonPutFromString[A](f: A => String): Put[A] =
    Put.Advanced.other[PGobject](
      NonEmptyList.of("json"),
    ).tcontramap { a =>
      val o = new PGobject
      o.setType("json")
      o.setValue(f(a))
      o
    }

  def jsonbGetFromString[A: TypeName](f: String => Either[String, A]): Get[A] =
    Get.Advanced.other[PGobject](
      NonEmptyList.of("jsonb"),
    ).temap { obj =>
      f(obj.getValue)
    }

  def jsonbPutFromString[A](f: A => String): Put[A] =
    Put.Advanced.other[PGobject](
      NonEmptyList.of("jsonb"),
    ).tcontramap { a =>
      val o = new PGobject
      o.setType("jsonb")
      o.setValue(f(a))
      o
    }
}

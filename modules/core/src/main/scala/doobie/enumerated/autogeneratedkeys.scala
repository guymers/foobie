// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.enumerated

import cats.kernel.Eq
import cats.kernel.instances.int.*

import java.sql.Statement.*

/** @group Types */
sealed abstract class AutoGeneratedKeys(val toInt: Int) extends Product with Serializable

/** @group Modules */
object AutoGeneratedKeys {

  /** @group Values */
  case object ReturnGeneratedKeys extends AutoGeneratedKeys(RETURN_GENERATED_KEYS)

  /** @group Values */
  case object NoGeneratedKeys extends AutoGeneratedKeys(NO_GENERATED_KEYS)

  def fromInt(n: Int): Option[AutoGeneratedKeys] =
    Some(n) collect {
      case ReturnGeneratedKeys.toInt => ReturnGeneratedKeys
      case NoGeneratedKeys.toInt => NoGeneratedKeys
    }

  implicit val EqAutoGeneratedKeys: Eq[AutoGeneratedKeys] =
    Eq.by(_.toInt)

}

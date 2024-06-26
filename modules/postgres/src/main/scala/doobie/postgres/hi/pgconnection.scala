// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.hi

import doobie.postgres.PFPC
import doobie.postgres.free.copymanager.CopyManagerIO
import doobie.postgres.free.largeobjectmanager.LargeObjectManagerIO
import doobie.postgres.free.pgconnection.PGConnectionIO
import org.postgresql.PGNotification

object pgconnection {

  val getBackendPID: PGConnectionIO[Int] =
    PFPC.getBackendPID

  def getCopyAPI[A](k: CopyManagerIO[A]): PGConnectionIO[A] =
    PFPC.getCopyAPI.flatMap(s => PFPC.embed(s, k)) // N.B. no need to close()

  def getLargeObjectAPI[A](k: LargeObjectManagerIO[A]): PGConnectionIO[A] =
    PFPC.getLargeObjectAPI.flatMap(s => PFPC.embed(s, k)) // N.B. no need to close()

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  val getNotifications: PGConnectionIO[List[PGNotification]] =
    PFPC.getNotifications map {
      case null => Nil
      case ns => ns.toList
    }

  val getPrepareThreshold: PGConnectionIO[Int] =
    PFPC.getPrepareThreshold

  def setPrepareThreshold(threshold: Int): PGConnectionIO[Unit] =
    PFPC.setPrepareThreshold(threshold)

}

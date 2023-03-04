// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie

package object postgres {

  type CopyInIO[A] = doobie.postgres.free.copyin.CopyInIO[A]
  type CopyManagerIO[A] = doobie.postgres.free.copymanager.CopyManagerIO[A]
  type CopyOutIO[A] = doobie.postgres.free.copyout.CopyOutIO[A]
  type LargeObjectIO[A] = doobie.postgres.free.largeobject.LargeObjectIO[A]
  type LargeObjectManagerIO[A] = doobie.postgres.free.largeobjectmanager.LargeObjectManagerIO[A]
  type PGConnectionIO[A] = doobie.postgres.free.pgconnection.PGConnectionIO[A]

  val PFCI = doobie.postgres.free.copyin
  val PFCM = doobie.postgres.free.copymanager
  val PFCO = doobie.postgres.free.copyout
  val PFLO = doobie.postgres.free.largeobject
  val PFLOM = doobie.postgres.free.largeobjectmanager
  val PFPC = doobie.postgres.free.pgconnection

  val PHPC = doobie.postgres.hi.pgconnection
  val PHC = doobie.postgres.hi.connection
  val PHLO = doobie.postgres.hi.largeobject
  val PHLOM = doobie.postgres.hi.largeobjectmanager
  val PHLOS = doobie.postgres.hi.lostreaming

  object implicits
    extends Instances
    with syntax.ToPostgresMonadErrorOps
    with syntax.ToFragmentOps
    with syntax.ToPostgresExplainOps
}

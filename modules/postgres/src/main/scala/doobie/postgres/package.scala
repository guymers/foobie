// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie

package object postgres {

  val PFCI = doobie.postgres.free.copyin
  val PFCM = doobie.postgres.free.copymanager
  val PFCO = doobie.postgres.free.copyout
  val PFLO = doobie.postgres.free.largeobject
  val PFLOM = doobie.postgres.free.largeobjectmanager
  val PFPC = doobie.postgres.free.pgconnection

  val PHPC = doobie.postgres.hi.PHPC
  val PHC = doobie.postgres.hi.PHC
  val PHLO = doobie.postgres.hi.PHLO
  val PHLOM = doobie.postgres.hi.PHLOM
  val PHLOS = doobie.postgres.hi.PHLOS
}

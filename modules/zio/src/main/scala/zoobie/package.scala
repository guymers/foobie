import zio.ZIO

package object zoobie {

  type DBIO[A] = ZIO[Any, DatabaseError, A]
}

/*
 * Copyright (c) 2013-18 Miles Sabin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package doobie.util

import scala.annotation.implicitNotFound

// from https://github.com/milessabin/shapeless/blob/v2.3.10/core/src/main/scala/shapeless/package.scala#L47
@implicitNotFound("${A} must not be a subtype of ${B}")
trait <:!<[A, B] extends Serializable
object <:!< {
  implicit def nsub[A, B]: A <:!< B = new <:!<[A, B] {}
  implicit def nsubAmbig1[A, B >: A]: A <:!< B = sys.error("Unexpected invocation")
  implicit def nsubAmbig2[A, B >: A]: A <:!< B = sys.error("Unexpected invocation")
}

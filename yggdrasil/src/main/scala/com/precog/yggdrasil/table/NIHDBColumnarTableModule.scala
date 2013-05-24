/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.yggdrasil
package table

import com.precog.bytecode.JType
import com.precog.common._
import com.precog.common.security._
import com.precog.yggdrasil.nihdb.NIHDBProjection
import com.precog.yggdrasil.vfs._

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.{Future, Promise}
import akka.pattern.AskSupport
import akka.util.Timeout

import com.weiglewilczek.slf4s.Logging

import org.joda.time.DateTime

import java.io.File

import scalaz._
import scalaz.std.list._
import scalaz.syntax.monad._
import scalaz.syntax.traverse._

import TableModule._

// TODO: rename to VFSColumnarTableModule. Nothing NIHDB-specific here
trait NIHDBColumnarTableModule extends BlockStoreColumnarTableModule[Future] with SecureVFSModule[Future, Slice] with AskSupport with Logging {
  def vfs: SecureVFS

  trait NIHDBColumnarTableCompanion extends BlockStoreColumnarTableCompanion {
    def load(table: Table, apiKey: APIKey, tpe: JType): Future[Table] = {
      logger.debug("Starting load from " + table.toJson)
      for {
        paths <- pathsM(table)
        tableE = paths.toList.traverse[({ type l[a] = EitherT[Future, ResourceError, a] })#l, ProjectionLike[Future, Slice]] { path =>
          logger.debug("  Loading path: " + path)
          vfs.readProjection(apiKey, path, Version.Current)
        } map { projections =>
          val length = projections.map(_.length).sum
          logger.debug("Loading from projections: " + projections)
          Table(projections.foldLeft(StreamT.empty[Future, Slice]) { (acc, proj) =>
            // FIXME: Can Schema.flatten return Option[Set[ColumnRef]] instead?
            val constraints = proj.structure.map { struct => 
              Some(Schema.flatten(tpe, struct.toList)) 
            }

            acc ++ StreamT.wrapEffect(constraints map { c => proj.getBlockStream(c) })
          }, ExactSize(length))
        }

        table <- tableE valueOr { errors =>
          sys.error("Unable to load table for paths %s as %s: %s".format(paths, apiKey, errors.toString))
        }
      } yield table
    }
  }
}

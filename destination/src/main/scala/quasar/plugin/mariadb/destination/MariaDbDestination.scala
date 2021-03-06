/*
 * Copyright 2020 Precog Data
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

package quasar.plugin.mariadb.destination

import quasar.plugin.mariadb._

import scala._, Predef._

import cats.data.NonEmptyList
import cats.effect.{ConcurrentEffect, Timer}

import doobie.Transactor

import monocle.Prism

import org.slf4s.Logger

import quasar.api.{ColumnType, Label}
import quasar.api.push.TypeCoercion
import quasar.connector.MonadResourceErr
import quasar.connector.destination.{Constructor, Destination, ResultSink}
import quasar.plugin.jdbc.destination.{JdbcCreateSink, WriteMode}

private[destination] final class MariaDbDestination[F[_]: ConcurrentEffect: MonadResourceErr: Timer](
    writeMode: WriteMode,
    xa: Transactor[F],
    logger: Logger)
    extends Destination[F] {

  import MariaDbType._

  type Type = MariaDbType
  type TypeId = MariaDbTypeId

  val destinationType = MariaDbDestinationModule.destinationType

  val createSink: ResultSink.CreateSink[F, Type] =
    ResultSink.CreateSink(
      MariaDbCsvConfig,
      JdbcCreateSink[F, Type](MariaDbHygiene, logger)(CsvCreateSink(writeMode, xa, logger)))

  val sinks = NonEmptyList.one(createSink)

  val typeIdOrdinal: Prism[Int, TypeId] =
    Prism(MariaDbDestination.OrdinalMap.get(_))(_.ordinal)

  val typeIdLabel: Label[TypeId] =
    Label.label[TypeId](_.toString)

  def coerce(tpe: ColumnType.Scalar): TypeCoercion[TypeId] = {
    def satisfied(t: TypeId, ts: TypeId*) =
      TypeCoercion.Satisfied(NonEmptyList(t, ts.toList))

    tpe match {
      case ColumnType.Boolean => satisfied(BOOLEAN)

      case ColumnType.LocalTime => satisfied(TIME)
      case ColumnType.LocalDate => satisfied(DATE)
      case ColumnType.LocalDateTime => satisfied(DATETIME)

      case ColumnType.Number =>
        satisfied(
          DOUBLE,
          INT,
          DECIMAL,
          BIGINT,
          MEDIUMINT,
          SMALLINT,
          TINYINT,
          YEAR,
          FLOAT)

      case ColumnType.String =>
        satisfied(
          TEXT,
          VARCHAR,
          MEDIUMTEXT,
          LONGTEXT,
          CHAR,
          VARBINARY,
          BINARY,
          BLOB,
          MEDIUMBLOB,
          LONGBLOB,
          TINYTEXT,
          TINYBLOB)

      case ColumnType.OffsetTime =>
        TypeCoercion.Unsatisfied(List(ColumnType.LocalTime), None)

      case ColumnType.OffsetDate =>
        TypeCoercion.Unsatisfied(List(ColumnType.LocalDate), None)

      case ColumnType.OffsetDateTime =>
        TypeCoercion.Unsatisfied(List(ColumnType.LocalDateTime), None)

      case _ => TypeCoercion.Unsatisfied(Nil, None)
    }
  }

  def construct(id: TypeId): Either[Type, Constructor[Type]] =
    id match {
      case tpe: MariaDbTypeId.SelfIdentified => Left(tpe)
      case hk: MariaDbTypeId.HigherKinded => Right(hk.constructor)
    }
}

object MariaDbDestination {
  val OrdinalMap: Map[Int, MariaDbTypeId] =
    MariaDbTypeId.allIds
      .toList
      .map(id => (id.ordinal, id))
      .toMap
}

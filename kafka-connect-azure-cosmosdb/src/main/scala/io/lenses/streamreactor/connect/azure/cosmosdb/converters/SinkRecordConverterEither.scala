/*
 * Copyright 2017-2025 Lenses.io Ltd
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
package io.lenses.streamreactor.connect.azure.cosmosdb.converters

import com.azure.cosmos.implementation.Document
import org.apache.kafka.connect.sink.SinkRecord
import org.json4s.JValue

import java.util
import scala.util.Try

/**
 * Thin Either-based wrapper for SinkRecordConverter, converting thrown exceptions into Left[Throwable].
 * Provides safe conversion from Map, Struct, or JSON to Cosmos DB Document, capturing any thrown errors.
 */
object SinkRecordConverterEither {

  /**
   * Converts a Java Map to a Cosmos DB Document, capturing any thrown errors as Left.
   *
   * @param map The Java Map to convert.
   * @return    Either a Throwable or the resulting Document.
   */
  def fromMap(map: util.Map[String, AnyRef]): Either[Throwable, Document] =
    Try(SinkRecordConverter.fromMap(map)).toEither

  /**
   * Converts a SinkRecord with a Struct value to a Cosmos DB Document, capturing any thrown errors as Left.
   *
   * @param record The SinkRecord to convert.
   * @return       Either a Throwable or the resulting Document.
   */
  def fromStruct(record: SinkRecord): Either[Throwable, Document] = Try(SinkRecordConverter.fromStruct(record)).toEither

  /**
   * Converts a JSON JValue to a Cosmos DB Document, capturing any thrown errors as Left.
   *
   * @param record The JSON JValue to convert.
   * @return       Either a Throwable or the resulting Document.
   */
  def fromJson(record: JValue): Either[Throwable, Document] = Try(SinkRecordConverter.fromJson(record)).toEither

}

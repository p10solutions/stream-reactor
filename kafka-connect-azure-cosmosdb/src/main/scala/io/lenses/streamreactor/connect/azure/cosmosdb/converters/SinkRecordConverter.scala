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

import com.azure.cosmos.CosmosItemSerializer
import com.azure.cosmos.implementation.Document
import io.lenses.streamreactor.connect.azure.cosmosdb.Json
import org.apache.kafka.connect.data._
import org.apache.kafka.connect.errors.DataException
import org.apache.kafka.connect.sink.SinkRecord
import org.json4s.JsonAST._
import org.json4s.JValue

import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util
import java.util.TimeZone
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.CollectionHasAsScala

/**
 * Utility for converting Kafka Connect data (Struct, Map, Array, etc.) into Azure Cosmos DB Document objects.
 * Handles all supported Connect schema types, including logical types (Date, Time, Timestamp, Decimal).
 * Provides conversion from Struct, Map, and JSON to Document, with robust error handling.
 */
private[converters] object SinkRecordConverter {
  private val ISO_DATE_FORMAT: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  private val TIME_FORMAT:     SimpleDateFormat = new SimpleDateFormat("HH:mm:ss.SSSZ")

  ISO_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"))

  /**
   * Converts a Java Map to a Cosmos DB Document by serializing it as JSON.
   *
   * @param map The Java Map to convert.
   * @return    The resulting Document.
   */
  def fromMap(map: util.Map[String, AnyRef]): Document = new Document(Json.toJson(map))

  /**
   * Converts a Kafka SinkRecord with a Struct value to a Cosmos DB Document.
   * Throws if the value is not a Struct or if the schema does not match.
   *
   * @param record The SinkRecord to convert.
   * @return       The resulting Document.
   * @throws IllegalArgumentException if the value is not a Struct.
   * @throws DataException if the schema does not match or required fields are missing.
   */
  def fromStruct(record: SinkRecord): Document = {
    if (!record.value().isInstanceOf[Struct]) {
      throw new IllegalArgumentException(s"Expecting a Struct. ${record.value.getClass}")
    }
    // We let it fail in case of a non struct (shouldn't be the case)
    convertToDocument(record.valueSchema(), record.value()).asInstanceOf[Document]
  }

  /**
   * Recursively converts a Connect value (with schema) to a Java object suitable for Cosmos DB.
   * Handles all supported Connect schema types, including logical types and nested structures.
   *
   * @param schema The Connect schema.
   * @param value  The value to convert.
   * @return       The converted value (Document, List, primitive, etc.).
   * @throws DataException if the value does not match the schema or is missing required fields.
   */
  @tailrec
  private def convertToDocument(schema: Schema, value: Any): Any =
    Option(value) match {
      case None =>
        if (schema == null) null
        else {
          if (schema.defaultValue != null) convertToDocument(schema, schema.defaultValue)
          else if (schema.isOptional) null
          else
            throw new DataException("Conversion error: null value for field that is required and has no default value")
        }
      case Some(_) =>
        try {
          val schemaType = Option(schema).map(_.`type`())
            .orElse(Option(ConnectSchema.schemaType(value.getClass)))
            .getOrElse(throw new DataException(s"Class [${value.getClass}] does not have corresponding schema type."))

          schemaType match {
            case Schema.Type.INT32 =>
              if (schema != null && Date.LOGICAL_NAME == schema.name)
                ISO_DATE_FORMAT.format(Date.toLogical(schema, value.asInstanceOf[Int]))
              else if (schema != null && Time.LOGICAL_NAME == schema.name)
                TIME_FORMAT.format(Time.toLogical(schema, value.asInstanceOf[Int]))
              else value

            case Schema.Type.INT64 =>
              if (Timestamp.LOGICAL_NAME == schema.name)
                Timestamp.fromLogical(schema, value.asInstanceOf[(java.util.Date)])
              else value

            case Schema.Type.STRING => value.asInstanceOf[CharSequence].toString
            case Schema.Type.BYTES  => handleBytes(schema, value)
            case Schema.Type.ARRAY  => handleArray(schema, value)
            case Schema.Type.MAP    => handleMap(schema, value)
            case Schema.Type.STRUCT => handleStruct(schema, value)

            case _ => value
          }
        } catch {
          case _: ClassCastException =>
            throw new DataException(s"Invalid type for [${schema.`type`}], type [${value.getClass}]")
        }
    }

  /**
   * Handles conversion of BYTES schema type, including Decimal logical type.
   *
   * @param schema The Connect schema.
   * @param value  The value to convert.
   * @return       The converted value (BigDecimal or Array[Byte]).
   * @throws DataException if the value is not a valid bytes type.
   */
  private def handleBytes(schema: Schema, value: Any) =
    if (Decimal.LOGICAL_NAME == schema.name) value.asInstanceOf[BigDecimal]
    else
      value match {
        case arrayByte: Array[Byte] => arrayByte
        case buffer:    ByteBuffer  => buffer.array
        case _ => throw new DataException(s"Invalid type for bytes type [${value.getClass}]")
      }

  /**
   * Handles conversion of ARRAY schema type.
   *
   * @param schema The Connect schema.
   * @param value  The value to convert (should be a Java Collection).
   * @return       A Java ArrayList of converted elements.
   */
  private def handleArray(schema: Schema, value: Any) = {
    val valueSchema = Option(schema).map(_.valueSchema()).orNull
    val list        = new util.ArrayList[Any]
    value
      .asInstanceOf[util.Collection[_]].asScala
      .foreach { elem =>
        list.add(convertToDocument(valueSchema, elem))
      }
    list
  }

  /**
   * Handles conversion of MAP schema type. String-keyed maps become Documents, others become ArrayLists of key-value pairs.
   *
   * @param schema The Connect schema.
   * @param value  The value to convert (should be a Java Map).
   * @return       A Document or ArrayList, depending on key type.
   */
  private def handleMap(schema: Schema, value: Any) = {
    val map = value.asInstanceOf[util.Map[_, _]]
    // If true, using string keys and JSON object; if false, using non-string keys and Array-encoding
    val objectMode: Boolean = Option(schema)
      .map(_.keySchema.`type` == Schema.Type.STRING)
      .getOrElse(map.entrySet().asScala.headOption.forall(_.getKey.isInstanceOf[String]))

    if (objectMode) {
      // FIX: Previously, the code set the entire map as a field with the map's string representation as the key.
      // Now, for string-keyed maps, we set each key/value as a field in the resulting Document.
      val obj: Document = new Document()
      buildContentForMapSource(schema, map) {
        case (key: String, value) =>
          obj.set(key, value, CosmosItemSerializer.DEFAULT_SERIALIZER)
        case (key, value) =>
          // Defensive: if key is not a string, fallback to string representation
          obj.set(key.toString, value, CosmosItemSerializer.DEFAULT_SERIALIZER)
      }
      obj
    } else {
      val list: util.ArrayList[Any] = new util.ArrayList[Any]()
      buildContentForMapSource(schema, map) {
        case (key, value) =>
          val innerArray = new util.ArrayList[Any]()
          innerArray.add(key)
          innerArray.add(value)
          val _ = list.add(innerArray)
      }
      list
    }
  }

  /**
   * Helper to iterate over map entries and apply a function to each key/value pair, converting them as needed.
   */
  private def buildContentForMapSource(schema: Schema, map: util.Map[_, _])(fn: (Any, Any) => Unit): Unit =
    for (entry <- map.entrySet.asScala) {
      val keySchema   = Option(schema).map(_.keySchema()).orNull
      val valueSchema = Option(schema).map(_.valueSchema).orNull

      val mapKey = convertToDocument(keySchema, entry.getKey)
      // Don't add null
      Option(convertToDocument(valueSchema, entry.getValue))
        .foreach { mapValue =>
          fn(mapKey, mapValue)
        }
    }

  /**
   * Handles conversion of STRUCT schema type to a Document, recursively converting all fields.
   *
   * @param schema The Struct schema.
   * @param value  The Struct value.
   * @return       The resulting Document.
   * @throws DataException if the schema does not match or required fields are missing.
   */
  private def handleStruct(schema: Schema, value: Any) = {
    val struct = value.asInstanceOf[Struct]
    if (struct.schema != schema) throw new DataException("Mismatching schema.")

    schema.fields.asScala
      .foldLeft(new Document) { (document, field) =>
        Option(convertToDocument(field.schema, struct.get(field)))
          .foreach {
            v => document.set(field.name, v, CosmosItemSerializer.DEFAULT_SERIALIZER)
          }
        document
      }
  }

  /**
   * Converts a JSON JValue to a Cosmos DB Document.
   *
   * @param record The JSON JValue.
   * @return       The resulting Document.
   * @throws IllegalArgumentException if the input is not a valid JSON object.
   */
  def fromJson(record: JValue): Document = {
    def convert(name: String, jvalue: JValue, document: Document): Document = {
      def convertArray(array: JArray): util.ArrayList[Any] = {
        val list = new util.ArrayList[Any]()
        array.children.filter {
          case JNull | JNothing => false
          case _                => true
        }.map {
          case JObject(values) => values.foldLeft(new Document) { case (d, (n, j)) => convert(n, j, d) }
          case JBool(b)        => b
          case JDecimal(d)     => d.toDouble
          case JDouble(d)      => d
          case JInt(i)         => i.toLong
          case JLong(l)        => l
          case JString(s)      => s
          case arr: JArray => convertArray(arr)
          case other => throw new NotImplementedError(s"match for $other (${other.getClass}) type not implemented")
        }.foreach(list.add)
        list
      }

      val value = jvalue match {
        case arr: JArray => convertArray(arr)
        case JBool(b)        => b
        case JDecimal(d)     => d.toDouble
        case JDouble(d)      => d
        case JInt(i)         => i.toLong
        case JLong(l)        => l
        case JNothing        => null
        case JNull           => null
        case JString(s)      => s
        case JObject(values) => values.foldLeft(new Document) { case (d, (n, j)) => convert(n, j, d) }
        case other           => throw new NotImplementedError(s"match for $other (${other.getClass}) type not implemented")
      }
      Option(value).map { v =>
        document.set(name, v, CosmosItemSerializer.DEFAULT_SERIALIZER)
        document
      }.getOrElse(document)
    }

    record match {
      case jobj: JObject =>
        jobj.obj.foldLeft(new Document) {
          case (d, JField(n, j)) => convert(n, j, d)
          case other             => throw new NotImplementedError(s"match for $other (${other.getClass}) type not implemented")
        }
      case _ => throw new IllegalArgumentException("Can't convert invalid json!")
    }
  }
}

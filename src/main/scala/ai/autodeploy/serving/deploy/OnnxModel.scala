/*
 * Copyright (c) 2019-2020 AutoDeployAI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.autodeploy.serving.deploy

import java.nio._
import java.nio.file.Path

import ai.autodeploy.serving.errors._
import ai.autodeploy.serving.model.{Field, PredictRequest, PredictResponse, RecordSpec}
import ai.autodeploy.serving.utils.DataUtils._
import ai.autodeploy.serving.utils.Utils._
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime._

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Using}

case class InputTensor(name: String, info: TensorInfo)

case class InputsWrapper(inputs: java.util.Map[String, OnnxTensor]) extends AutoCloseable {
  override def close(): Unit = {
    val it = inputs.values.iterator()
    while (it.hasNext) {
      safeClose(it.next)
    }
  }
}

/**
 * Supports the model of Open Neural Network Exchange (ONNX)
 */
class OnnxModel(val session: OrtSession, val env: OrtEnvironment) extends PredictModel {

  val inputTensors = session.getInputInfo.asScala.map[String, InputTensor](x =>
    x._1 -> InputTensor(x._2.getName, x._2.getInfo.asInstanceOf[TensorInfo]))

  override def predict(payload: PredictRequest): PredictResponse = {

    val requestedOutput = payload.filter.flatMap(x => toOption(x)).map(_.toSet.asJava).getOrElse(session.getOutputNames)

    val result: RecordSpec = if (payload.X.records.isDefined) {
      RecordSpec(records = payload.X.records.map(records => {
        records.map(record => {
          Using.Manager { use =>
            val wrapper = use(createInputsWrapper(record))
            val result = use(session.run(wrapper.inputs, requestedOutput))

            result.asScala.map(x => {
              x.getKey -> x.getValue.getValue
            }).toMap
          } match {
            case Success(value)     => value
            case Failure(exception) => throw exception
          }
        })
      }))
    } else {
      val columns = payload.X.columns.get
      var outputColumns: Seq[String] = null
      val outputData = payload.X.data.map(data => {
        data.map(values => {
          val record = columns.zip(values).toMap
          Using.Manager { use =>
            val wrapper = use(createInputsWrapper(record))
            val result = use(session.run(wrapper.inputs, requestedOutput))
            val scalaResult = result.asScala
            if (outputColumns == null) {
              outputColumns = scalaResult.map(_.getKey).toSeq
            }
            scalaResult.map(_.getValue.getValue).toSeq
          } match {
            case Success(value)     => value
            case Failure(exception) => throw exception
          }
        })
      })
      RecordSpec(columns = Some(outputColumns), data = outputData)
    }

    PredictResponse(result)
  }

  override def `type`(): String = "ONNX"

  override def runtime(): String = "ONNX Runtime"

  override def serialization(): String = "onnx"

  override def predictors(): Seq[Field] = {
    session.getInputInfo.asScala.values.toSeq.map(x => {
      toField(x)
    })
  }

  override def outputs(): Seq[Field] = {
    session.getOutputInfo.asScala.values.toSeq.map(x => {
      toField(x)
    })
  }

  override def close(): Unit = {
    safeClose(session)
  }

  private def createInputsWrapper(record: Map[String, Any]): InputsWrapper = {
    val inputs = new java.util.HashMap[String, OnnxTensor](inputTensors.size)
    for ((name, tensor) <- inputTensors) {
      inputs.put(name, convertToTensor(name, tensor.info, record.get(name)))
    }
    InputsWrapper(inputs)
  }

  private def toField(node: NodeInfo): Field = {
    val name = node.getName
    node.getInfo match {
      case x: TensorInfo => Field(name, fieldType(x), shape = if (x.isScalar) None else Some(x.getShape.toList))
      case x             => Field(name, fieldType(x))
    }
  }

  private def fieldType(info: ValueInfo): String = info match {
    case x: TensorInfo   => s"tensor(${
      x.`type`.toString.toLowerCase()
    })"
    case x: MapInfo      => s"map(${
      x.keyType
    },${
      x.valueType
    })"
    case x: SequenceInfo => {
      val elementType = if (x.isSequenceOfMaps) {
        s"map(${
          x.mapInfo.keyType
        },${
          x.mapInfo.valueType
        })"
      } else s"${
        x.sequenceType
      }"
      s"seq(${
        elementType.toLowerCase
      })"
    }
  }


  private def convertToTensor(name: String, tensorInfo: TensorInfo, inputValue: Option[Any]): OnnxTensor = inputValue match {
    case Some(value) => {
      import OnnxJavaType._
      val expectedShape = tensorInfo.getShape
      value match {
        case buffer: ByteBuffer => {
          tensorInfo.`type` match {
            case FLOAT   => {
              OnnxTensor.createTensor(env, buffer.asFloatBuffer(), expectedShape)
            }
            case DOUBLE  => {
              OnnxTensor.createTensor(env, buffer.asDoubleBuffer(), expectedShape)
            }
            case INT8    => {
              OnnxTensor.createTensor(env, buffer, expectedShape)
            }
            case INT16   => {
              OnnxTensor.createTensor(env, buffer.asShortBuffer(), expectedShape)
            }
            case INT32   => {
              OnnxTensor.createTensor(env, buffer.asIntBuffer(), expectedShape)
            }
            case INT64   => {
              OnnxTensor.createTensor(env, buffer.asLongBuffer(), expectedShape)
            }
            case BOOL    => {
              OnnxTensor.createTensor(env, buffer, expectedShape)
            }
            case STRING  => {
              ???
            }
            case UNKNOWN => {
              throw UnknownDataTypeException(name)
            }
          }
        }
        case _                  => {
          val shape = shapeOfValue(value)
          val count = elementCount(shape)
          if (count != elementCount(expectedShape)) {
            throw ShapeMismatchException(shape, expectedShape)
          }

          val intCount = count.toInt
          tensorInfo.`type` match {
            case FLOAT   => {
              val data = copyToBuffer[Float](intCount, value)
              OnnxTensor.createTensor(env, FloatBuffer.wrap(data), expectedShape)
            }
            case DOUBLE  => {
              val data = copyToBuffer[Double](intCount, value)
              OnnxTensor.createTensor(env, DoubleBuffer.wrap(data), expectedShape)
            }
            case INT8    => {
              val data = copyToBuffer[Byte](intCount, value)
              OnnxTensor.createTensor(env, ByteBuffer.wrap(data), expectedShape)
            }
            case INT16   => {
              val data = copyToBuffer[Short](intCount, value)
              OnnxTensor.createTensor(env, ShortBuffer.wrap(data), expectedShape)
            }
            case INT32   => {
              val data = copyToBuffer[Int](intCount, value)
              OnnxTensor.createTensor(env, IntBuffer.wrap(data), expectedShape)
            }
            case INT64   => {
              val data = copyToBuffer[Long](intCount, value)
              OnnxTensor.createTensor(env, LongBuffer.wrap(data), expectedShape)
            }
            case BOOL    => {
              val data = copyToBuffer[Boolean](intCount, value)
              OnnxTensor.createTensor(env, OrtUtil.reshape(data, expectedShape))
            }
            case STRING  => {
              val data = copyToBuffer[String](intCount, value)
              OnnxTensor.createTensor(env, data, expectedShape)
            }
            case UNKNOWN => {
              throw UnknownDataTypeException(name)
            }
          }
        }
      }
    }
    case _           => throw MissingValueException(name, fieldType(tensorInfo), tensorInfo.getShape)
  }

  private def copyToBuffer[@specialized(Float, Double, Byte, Short, Int, Long, Boolean) T: ClassTag](len: Int, value: Any): Array[T] = {
    val data = Array.ofDim[T](len)

    val tag = implicitly[ClassTag[T]]
    tag.runtimeClass match {
      case java.lang.Float.TYPE   => copy[Float](data.asInstanceOf[Array[Float]], 0, value, anyToFloat)
      case java.lang.Double.TYPE  => copy[Double](data.asInstanceOf[Array[Double]], 0, value, anyToDouble)
      case java.lang.Byte.TYPE    => copy[Byte](data.asInstanceOf[Array[Byte]], 0, value, anyToByte)
      case java.lang.Short.TYPE   => copy[Short](data.asInstanceOf[Array[Short]], 0, value, anyToShort)
      case java.lang.Integer.TYPE => copy[Int](data.asInstanceOf[Array[Int]], 0, value, anyToInt)
      case java.lang.Long.TYPE    => copy[Long](data.asInstanceOf[Array[Long]], 0, value, anyToLong)
      case java.lang.Boolean.TYPE => copy[Boolean](data.asInstanceOf[Array[Boolean]], 0, value, anyToBoolean)
      case _                      => copy[String](data.asInstanceOf[Array[String]], 0, value, anyToString)
    }

    data
  }

  private def copy[T](data: Array[T], pos: Int, value: Any, converter: Any => T): Int = value match {
    case seq: Seq[_] => {
      var idx = pos
      seq.foreach {
        x =>
          idx = copy(data, idx, x, converter)
      }
      idx
    }
    case _           => {
      data.update(pos, converter(value))
      pos + 1
    }
  }
}


object OnnxModel extends ModelLoader {

  lazy val env = OrtEnvironment.getEnvironment()
  lazy val opts = {
    (new SessionOptions)
  }

  def load(path: Path): OnnxModel = {
    try {
      val modelPath = path.toAbsolutePath.toString
      val session = env.createSession(modelPath, opts)
      new OnnxModel(session, env)
    } catch {
      case ex: java.lang.UnsatisfiedLinkError => throw OnnxRuntimeLibraryNotFoundError(ex.getMessage)
      case ex: Throwable                      => throw InvalidModelException("ONNX", ex.getMessage)
    }
  }

}
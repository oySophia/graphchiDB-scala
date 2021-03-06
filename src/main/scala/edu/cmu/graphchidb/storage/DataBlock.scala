package edu.cmu.graphchidb.storage

import java.nio.ByteBuffer
import java.io.File

/**
 * DataBlock is a low level storage object, that stores key-value pairs.
 * @author Aapo Kyrola
 */

trait ByteConverter[T] {
  def fromBytes(bb: ByteBuffer) : T
  def toBytes(v: T, out: ByteBuffer) : Unit
  def sizeOf: Int
}

object ByteConverters {
  implicit object IntByteConverter extends ByteConverter[Int] {
    override def fromBytes(bb: ByteBuffer) : Int = {
      bb.getInt
    }
    override def toBytes(v: Int, bb: ByteBuffer) : Unit = {
      bb.putInt(v)
    }
    override def sizeOf = 4
  }

  implicit object ShortByteConverter extends ByteConverter[Short] {
    override def fromBytes(bb: ByteBuffer) : Short = {
      bb.getShort
    }
    override def toBytes(v: Short, bb: ByteBuffer) : Unit = {
      bb.putShort(v)
    }
    override def sizeOf = 2
  }

  implicit object FloatByteConverter extends ByteConverter[Float] {
    override def fromBytes(bb: ByteBuffer) : Float = {
      bb.getFloat
    }
    override def toBytes(v: Float, bb: ByteBuffer) : Unit = {
      bb.putFloat(v)
    }
    override def sizeOf = 4
  }

  implicit object LongByteConverter extends ByteConverter[Long] {
    override def fromBytes(bb: ByteBuffer) : Long = {
      bb.getLong
    }
    override def toBytes(v: Long, bb: ByteBuffer) : Unit = {
      bb.putLong(v)
    }
    override def sizeOf = 8
  }

  implicit  object ByteByteConverter extends ByteConverter[Byte] {
    override def fromBytes(bb: ByteBuffer) : Byte = {
      bb.get
    }
    override def toBytes(v: Byte, bb: ByteBuffer) : Unit = {
      bb.put(v)
    }
    override def sizeOf = 1
  }

}

trait DataBlock[T] extends IndexedByteStorageBlock {


  def get(idx: Int, byteBuffer: ByteBuffer)(implicit converter: ByteConverter[T]) : Option[T] = {
    if (readIntoBuffer(idx, byteBuffer)) {
      byteBuffer.rewind()
      Some(converter.fromBytes(byteBuffer))
    } else
      None
  }

  def get(idx: Int)(implicit converter: ByteConverter[T]) : Option[T] = {
    val byteBuffer = ByteBuffer.allocate(valueLength)
    get(idx, byteBuffer)(converter)
  }

  def set(idx: Int, value: T, bb: ByteBuffer)(implicit converter: ByteConverter[T]) : Unit = {
    converter.toBytes(value, bb)
    bb.rewind()
    writeFromBuffer(idx, bb)
  }

  def set(idx: Int, value: T)(implicit converter: ByteConverter[T]) : Unit = {
    val bb = ByteBuffer.allocate(converter.sizeOf)  // TODO reuse
    set(idx, value, bb)
  }

  def delete(): Unit

  def size(): Int

  def foldLeft[B](z: B)(op: (B, T, Int) => B)(implicit converter: ByteConverter[T]) : B  = {
    val n = size()
    var cum: B = z
    for( i <- 0 until n) {
      val xOpt : Option[T] = get(i)(converter)
      if (xOpt.isDefined) cum = op(cum, xOpt.get, i)

    }
    cum
  }



  def foreach(op: (Long,T) => Unit)(implicit converter: ByteConverter[T])  : Unit = {
    val n = size()
    for( i <- 0 until n) {
      val xOpt : Option[T] = get(i)(converter)
      if (xOpt.isDefined) op(i, xOpt.get)
    }
  }

  def updateAll(op: (Long, Option[T]) => T) (implicit converter: ByteConverter[T])  : Unit = {
    val n = size()
    for( i <- 0 until n) {
      val xOpt : Option[T] = get(i)(converter)
      val newVal = op(i, xOpt)
      set(i, newVal)
    }
  }

  def select(cond: (Long, T) => Boolean)(implicit converter: ByteConverter[T]) : Iterator[(Long, T)] = {
    val n = size()
    (0 until n).iterator.filter(i => {
      val xOpt : Option[T] = get(i)(converter)
      if (xOpt.isDefined) {
        cond(i, xOpt.get)
      } else {
        false
      }
    }).map(i => (i, get(i)(converter).get))
  }

}

/*
 * Internal low-level
 */
trait IndexedByteStorageBlock  {

  def valueLength: Int
  def readIntoBuffer(idx: Int, out: ByteBuffer) : Boolean
  def writeFromBuffer(idx: Int, in: ByteBuffer) : Unit

}

package edu.cmu.graphchidb

import edu.cmu.graphchi.ChiFilenames
import edu.cmu.graphchi.preprocessing.{EdgeProcessor, VertexProcessor, FastSharder, VertexIdTranslate}
import java.io.{IOException, FileOutputStream, File}
import edu.cmu.graphchi.VertexInterval

import scala.collection.JavaConversions._
import edu.cmu.graphchidb.storage._
import edu.cmu.graphchi.queries.{QueryCallback}
import edu.cmu.graphchidb.Util.async
import java.nio.{BufferUnderflowException, ByteBuffer}
import edu.cmu.graphchi.datablocks.{BytesToValueConverter, BooleanConverter}
import edu.cmu.graphchidb.queries.QueryResult
import java.{util, lang}
import edu.cmu.graphchidb.queries.internal.QueryResultContainer
import java.util.{Random, Date, Collections}
import edu.cmu.graphchidb.storage.inmemory.EdgeBuffer
import edu.cmu.graphchi.shards.{PointerUtil, QueryShard}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.text.SimpleDateFormat
import java.util.concurrent.locks.ReadWriteLock
import scala.actors.threadpool.locks.ReentrantReadWriteLock
import edu.cmu.graphchi.util.Sorting
import edu.cmu.graphchidb.compute.Computation
import sun.reflect.generics.reflectiveObjects.NotImplementedException
import java.util.concurrent.TimeUnit

// TODO: refactor: separate database creation and definition from the graphchidatabase class


object GraphChiDatabaseAdmin {

  def createDatabase(baseFilename: String) : Boolean= {

    // Temporary code!
    FastSharder.createEmptyGraph(baseFilename, 256, 1L<<33)
    true
  }


}


/**
 * Defines a sharded graphchi database.
 * @author Aapo Kyrola
 */
class GraphChiDatabase(baseFilename: String,  bufferLimit : Int = 10000000) {
  var numShards = 256
  val bufferParents = 4

  val vertexIdTranslate = VertexIdTranslate.fromFile(new File(ChiFilenames.getVertexTranslateDefFile(baseFilename, numShards)))
  val intervals = ChiFilenames.loadIntervals(baseFilename, numShards).toIndexedSeq
  val intervalLength = intervals(0).length()

  /* This array keeps track of the largest vertex id currently present in each interval. Due to the modulo-shuffling scheme,
     the vertex Ids start from the "bottom" of the interval lower bound.
   */

  def intervalContaining(dst: Long) = {
    val firstTry = intervals((dst / vertexIdTranslate.getVertexIntervalLength).toInt)
    if (firstTry.contains(dst)) {
      Some(firstTry)
    } else {
      println("Full interval scan...")
      intervals.find(_.contains(dst))
    }
  }

  var initialized = false

  val debugFile = new FileOutputStream(new File(baseFilename + ".debug.txt"))
  val format = new java.text.SimpleDateFormat("dd-MM-yyyy HH:mm:ss")


  val diskShardPurgeLock = new Object()

  /* Debug log */
  def log(msg: String) = {
    val str = format.format(new Date()) + "\t" + msg + "\n"
    debugFile.synchronized {
      debugFile.write(str.getBytes)
      debugFile.flush()
    }
  }

  def timed[R](blockName: String, block: => R): R = {
    val t0 = System.nanoTime()
    val result = block
    val t1 = System.nanoTime()
    log(blockName + " " +  (t1 - t0) / 1000000.0 + "ms")
    result
  }

  // TODO: hardcoded
  val shardSizeLimit = 2000000000L / 256L


  class DiskShard(levelIdx: Int,  _shardId : Int, splitIntervals: Seq[VertexInterval], parentShards: Seq[DiskShard]) {
    val persistentShardLock = new ReentrantReadWriteLock()
    val shardId = _shardId

    val myInterval = splitIntervals(levelIdx)


    var persistentShard = {
      try {
        new QueryShard(baseFilename, shardId, numShards, myInterval)
      } catch {
        case ioe: IOException => {
          // TODO: improve
          FastSharder.createEmptyShard(baseFilename, numShards, shardId)
          new QueryShard(baseFilename, shardId, numShards, myInterval)
        }
      }
    }

    def numEdges = persistentShard.getNumEdges

    def reset() : Unit = {
      persistentShardLock.writeLock().lock()
      try {
        persistentShard = new QueryShard(baseFilename, shardId, numShards, myInterval)
      } finally {
        persistentShardLock.writeLock().unlock()
      }
    }


    def find(edgeType: Byte, src: Long, dst: Long) : Option[Long] = {
      println("Shard %d find".format(shardId))
      persistentShardLock.readLock().lock()
      try {
        val idx = persistentShard.find(edgeType, src, dst)
        idx match {
          case null => None
          case _ => Some(idx)
        }
      } finally {
        persistentShardLock.readLock().unlock()
      }
    }



      def checkSize : Unit = {
      persistentShardLock.writeLock().lock()

      try {
        if (persistentShard.getNumEdges > shardSizeLimit && !parentShards.isEmpty) {
          persistentShardLock.writeLock().unlock()
          // Release lock so reads can continue while we wait for purge.
          // NOTE: there is slight change that this shard becomes even larger.
          diskShardPurgeLock.synchronized {
            persistentShardLock.writeLock().lock()
            if (persistentShard.getNumEdges > shardSizeLimit) {
              log("Shard %d  /%d too full --> merge upwards".format(_shardId, levelIdx))
              mergeToParents
            }
          }
        }
      } catch {
        case e: Exception => {
          e.printStackTrace()
          throw e }
      } finally {
        persistentShardLock.writeLock().unlock()
      }
    }

    def readIntoBuffer(destInterval: VertexInterval): EdgeBuffer = {
      val edgeSize = edgeEncoderDecoder.edgeSize
      val edgeColumns = columns(edgeIndexing)
      val workBuffer = ByteBuffer.allocate(edgeSize)
      val thisBuffer =  new EdgeBuffer(edgeEncoderDecoder, persistentShard.getNumEdges.toInt / 2, bufferId=(-1))
      val edgeIterator = persistentShard.edgeIterator()
      var i = 0
      while(edgeIterator.hasNext) {
        edgeIterator.next()
        if (destInterval.contains(edgeIterator.getDst)) {
          workBuffer.rewind()
          edgeColumns.foreach(c => c._2.readValueBytes(shardId, i, workBuffer))
          thisBuffer.addEdge(edgeIterator.getType, edgeIterator.getSrc, edgeIterator.getDst, workBuffer.array())
        }
        i += 1
      }
      thisBuffer.compact
    }

    def mergeToAndClear(destShards: Seq[DiskShard]) : Unit = {
      var totalMergedEdges = 0
      val edgeSize = edgeEncoderDecoder.edgeSize

      try {
        persistentShardLock.writeLock().lock()
        // Note: not parallel in order to not use too much memory (buffer flush is parallel)
        destShards.foreach( destShard => {
          val myEdges = readIntoBuffer(destShard.myInterval)
          val destEdges = destShard.readIntoBuffer(destShard.myInterval)

          val totalEdges = myEdges.numEdges + destEdges.numEdges
          totalMergedEdges += totalEdges
          val combinedSrc = new Array[Long](totalEdges.toInt)
          val combinedDstWithType = new Array[Long](totalEdges.toInt)
          val combinedValues = new Array[Byte](totalEdges.toInt * edgeSize)

          Sorting.mergeWithValues(myEdges.srcArray, myEdges.dstArrayWithType, myEdges.byteArray,
            destEdges.srcArray, destEdges.dstArrayWithType, destEdges.byteArray,
            combinedSrc, combinedDstWithType, combinedValues, edgeSize)

          log("Merging %d -> %d (%d edges)".format(shardId, destShard.shardId, totalEdges))

          // Write shard
          destShard.persistentShardLock.writeLock().lock()
          try {
            timed("diskshard-merge,writeshard", {
              FastSharder.writeAdjacencyShard(baseFilename, destShard.shardId, numShards, edgeSize, combinedSrc,
                combinedDstWithType, combinedValues, destShard.myInterval.getFirstVertex,
                destShard.myInterval.getLastVertex, true)
            })
            // TODO: consider synchronization
            // Write data columns, i.e replace the column shard with new data
            (0 until columns(edgeIndexing).size).foreach(columnIdx => {
              val columnBuffer = ByteBuffer.allocate(totalEdges.toInt * edgeEncoderDecoder.columnLength(columnIdx))
              EdgeBuffer.projectColumnToBuffer(columnIdx, columnBuffer, edgeEncoderDecoder, combinedValues, totalEdges.toInt)
              columns(edgeIndexing)(columnIdx)._2.recreateWithData(destShard.shardId, columnBuffer.array())
            })
            destShard.reset
          } catch {
            case  e:Exception => {
              e.printStackTrace()
              throw e
            }
          } finally {
            destShard.persistentShardLock.writeLock().unlock()
          }
        })

        // Empty my shard
        timed("diskshard-merge,emptymyshards", {
          FastSharder.createEmptyShard(baseFilename, numShards, shardId)
          (0 until columns(edgeIndexing).size).foreach(columnIdx => {
            columns(edgeIndexing)(columnIdx)._2.recreateWithData(shardId, new Array[Byte](0)) // Empty
          })
        })
        reset
      } catch {
        case  e:Exception => {
          e.printStackTrace()
          throw e
        }
      } finally {
        persistentShardLock.writeLock().unlock()
      }

      // Check if upstream shards are too big  -- not in parallel but in background thread
      // (lock guarantees that only one purge takes place at once)
      async  {
        destShards.foreach(destShard => destShard.checkSize)
      }
    }

    def mergeToParents = mergeToAndClear(parentShards)
  }

  case class EdgeBufferAndInterval(buffer: EdgeBuffer, interval: VertexInterval)

  case class BufferRef(bufferShardId: Int, nthBuffer: Int)

  val buffersPerBufferShard = intervals.size * bufferParents
  def edgeBufferId(bufferShardId: Int, parentBufferIdx: Int, nthBuffer: Int) = {
    assert(nthBuffer < buffersPerBufferShard)
    buffersPerBufferShard * bufferShardId + parentBufferIdx * intervals.size + nthBuffer
  }


  def bufferReference(bufferId: Int) = BufferRef(bufferId / buffersPerBufferShard, bufferId % buffersPerBufferShard)


  class BufferShard(bufferShardId: Int, _myInterval: VertexInterval,
                    parentShards:  Seq[DiskShard]) {
    var buffers = IndexedSeq[IndexedSeq[EdgeBufferAndInterval]]()
    var oldBuffers = IndexedSeq[IndexedSeq[EdgeBufferAndInterval]]()   // used in handout
    val myInterval = _myInterval
    val parentIntervals = parentShards.map(_.myInterval).toIndexedSeq
    val parentIntervalLength = parentIntervals.head.length()
    val firstDst = myInterval.getFirstVertex
    assert(firstDst == parentIntervals(0).getFirstVertex)

    assert(parentShards.size <= bufferParents)
    val intervalLength = intervals.head.length()

    def init() : Unit = {
      // TODO: Clean up!
      // Two dimensional buffer matrix where we have one buffer for each interval, divided
      // to buffers for each of the parents
      log("Init buffers: %d".format(bufferShardId))
      buffers = parentIntervals.map(parentInterval =>
        intervals.map(interval => EdgeBufferAndInterval(new EdgeBuffer(edgeEncoderDecoder,
          bufferId=edgeBufferId(bufferShardId, parentInterval.getId - parentIntervals.head.getId, interval.getId)), interval)))
      if (oldBuffers.isEmpty) oldBuffers = parentIntervals.map(parentInterval =>
        intervals.map(interval => EdgeBufferAndInterval(new EdgeBuffer(edgeEncoderDecoder,
          bufferId=edgeBufferId(bufferShardId, parentInterval.getId - parentIntervals.head.getId, interval.getId)), interval)))
    }

    // Note, to call these methods the acquirer need to hold the readlock
    def buffersForDstQuery(dst: Long) = {
      val parentIdx = ((dst - firstDst) / parentIntervalLength).toInt
      buffers(parentIdx) ++ oldBuffers(parentIdx)
    }
    def buffersForSrcQuery(src: Long) =
      buffers.map(bufs => bufs((src / intervalLength).toInt)) ++  oldBuffers.map(bufs => bufs((src / intervalLength).toInt))


    /* Buffer if chosen by src (shard is chosen by dst) */
    def bufferFor(src:Long, dst:Long) : EdgeBuffer = {
      val buffersByParent = buffers(((dst - firstDst) / parentIntervalLength).toInt)
      val firstTry = (src / intervalLength).toInt
      buffersByParent(firstTry).buffer
    }

    def oldBufferFor(src:Long, dst:Long) : EdgeBuffer = {
      val buffersByParent = oldBuffers(((dst - firstDst) / parentIntervalLength).toInt)
      val firstTry = (src / intervalLength).toInt
      buffersByParent(firstTry).buffer
    }

    def nthBuffer(nth: Int) = buffers(nth / intervals.size)(nth % intervals.size)

    val bufferLock = new ReentrantReadWriteLock()

    case class DelayedEdge(edgeType: Byte, src: Long, dst:Long, values: Any*)
    var delayedStack = List[DelayedEdge]()
    var delayedCount = 0

    def addEdge(edgeType: Byte, src: Long, dst:Long, values: Any*) : Unit = {
      // TODO: Handle if value outside of intervals
      // Kind of complicated logic... improve?
      var gotLock = false
      if (delayedCount > 10000) {
        println("Stalling... %d".format(delayedCount))    // Kind of hacky
        gotLock = bufferLock.writeLock().tryLock(1L, scala.actors.threadpool.TimeUnit.SECONDS)
      }

      if (gotLock || bufferLock.writeLock().tryLock()) {
        // Check for delayed edges
        try {
          if (delayedStack.nonEmpty) {
            delayedStack.foreach(e => bufferFor(e.src, e.dst).addEdge(e.edgeType, e.src, e.dst, e.values:_*))
            delayedStack = List[DelayedEdge]()
            delayedCount = 0
          }
          bufferFor(src, dst).addEdge(edgeType, src, dst, values:_*)
        } catch {
          case e: Exception => e.printStackTrace()
        } finally {
          bufferLock.writeLock().unlock()
        }
      } else {
        // Stalling
        delayedStack = delayedStack :+ DelayedEdge(edgeType, src, dst, values:_*)
        delayedCount += 1
      }
    }

    /* Returns buffer pointer for given edge, if found */
    def find(edgeType: Byte, src: Long, dst: Long) : Option[Long] = {
      bufferLock.readLock().lock()
      try {
        bufferFor(src, dst).find(edgeType, src, dst).orElse(oldBufferFor(src, dst).find(edgeType, src, dst))
      } finally {
        bufferLock.readLock().unlock()
      }
    }

    def getValue[T](edgeType: Byte, src:Long, dst:Long, columnIdx: Int) : Option[T] = {
      bufferLock.readLock().lock()
      try {
        val buffer = bufferFor(src, dst)
        val ptrOpt  = buffer.find(edgeType, src, dst)
        // Need to check old and new buffer...
        if (ptrOpt.isDefined) {
          val byteBuf = ByteBuffer.allocate(edgeEncoderDecoder.edgeSize)
          buffer.readEdgeIntoBuffer(PointerUtil.decodeBufferPos(ptrOpt.get), byteBuf)
          byteBuf.rewind
          val decoded = edgeEncoderDecoder.decode(byteBuf, src, dst)
          Some(decoded.values(columnIdx).asInstanceOf[T])
        } else {
          val oldBuffer = oldBufferFor(src, dst)
          if (ptrOpt.isDefined) {
            val byteBuf = ByteBuffer.allocate(edgeEncoderDecoder.edgeSize)
            oldBuffer.readEdgeIntoBuffer(PointerUtil.decodeBufferPos(ptrOpt.get), byteBuf)
            byteBuf.rewind
            val decoded = edgeEncoderDecoder.decode(byteBuf, src, dst)
            Some(decoded.values(columnIdx).asInstanceOf[T])
          } else {
            None
          }
        }
      } finally {
        bufferLock.readLock().unlock()
      }
    }

    val flushLock = new Object


    def update[T](edgeType: Byte, src: Long, dst: Long, columnIdx: Int, value: T) : Boolean  = {
      // Note: for update we use readLock as it does not alter the size of the data or move data
      if (find(edgeType, src, dst).isDefined) {
        flushLock.synchronized {         // Need flush lock
          bufferLock.readLock().lock()
          try {
            val buffer = bufferFor(src, dst)
            val ptrOpt  = buffer.find(edgeType, src, dst)
            if (ptrOpt.isDefined) {
              buffer.setColumnValue(PointerUtil.decodeBufferPos(ptrOpt.get), columnIdx, value)
              return true
            }  else { return false }
          } finally {
            bufferLock.readLock().unlock()
          }
        }
      } else {
        false
      }
    }


    def deleteAllEdgesForVertex(vertexId: Long, hasIn: Boolean, hasOut: Boolean) = {
      flushLock.synchronized {
        if (myInterval.contains(vertexId) && hasIn) {
          buffersForDstQuery(vertexId).foreach(b => b.buffer.deleteAllEdgesForVertex(vertexId))
        }
        if (hasOut) {
          buffersForSrcQuery(vertexId).foreach(b => b.buffer.deleteAllEdgesForVertex(vertexId))
        }
      }
    }

    def deleteEdge(edgeType: Byte, src: Long, dst: Long) : Boolean = {
      if (find(edgeType, src, dst).isDefined) {
        flushLock.synchronized {         // Need flush lock
          bufferLock.readLock().lock()
          try {
            val buffer = bufferFor(src, dst)
            val ptrOpt  = buffer.find(edgeType, src, dst)
            if (ptrOpt.isDefined) {
              buffer.deleteEdgeAt(PointerUtil.decodeBufferPos(ptrOpt.get))
              return true
            }  else { return false }
          } finally {
            bufferLock.readLock().unlock()
          }
        }
      } else {
        false
      }
    }

    def numEdgesInclDeletions = buffers.map(_.map(b => b.buffer.numEdges + b.buffer.deletedEdges).sum).sum
    def numEdges  = buffers.map(_.map(b => b.buffer.numEdges ).sum).sum


    def mergeToParentsAndClear() : Unit = {
      flushLock.synchronized {
        var totalMergedEdges = 0

        val edgeSize = edgeEncoderDecoder.edgeSize

        timed("mergeToAndClear %d".format(bufferShardId), {
          bufferLock.writeLock().lock()
          oldBuffers = buffers
          val numEdgesToMerge = oldBuffers.flatten.map(_.buffer.numEdges).sum

          try {
            init()
          } finally {
            bufferLock.writeLock().unlock()
          }
          try {
            parentShards.par.foreach( destShard => {
              // This prevents queries for that shard while buffer is being emptied.
              // TODO: improve

              val parentIdx = parentShards.indexOf(destShard)
              val parEdges = oldBuffers(parentIdx).map(_.buffer.numEdges).sum
              val myEdges = new EdgeBuffer(edgeEncoderDecoder, parEdges, bufferId=(-1))
              // Get edges from buffers
              timed("Edges from buffers", {
                oldBuffers(parentIdx).foreach( bufAndInt => {
                  val buffer = bufAndInt.buffer
                  val edgeIterator = buffer.edgeIterator
                  var i = 0
                  val workBuffer = ByteBuffer.allocate(edgeSize)

                  while(edgeIterator.hasNext) {
                    edgeIterator.next()
                    workBuffer.rewind()
                    buffer.readEdgeIntoBuffer(i, workBuffer)
                    // TODO: write directly to buffer
                    if (!_myInterval.contains(edgeIterator.getDst)) {
                      throw new IllegalStateException()
                    }

                    myEdges.addEdge(edgeIterator.getType, edgeIterator.getSrc, edgeIterator.getDst, workBuffer.array())
                    i += 1
                  }
                })
                if (myEdges.numEdges != parEdges) throw new IllegalStateException("Mismatch %d != %d".format(myEdges.numEdges,parEdges))
                assert(myEdges.numEdges == parEdges)
              })



              timed("sortEdges", {
                Sorting.sortWithValues(myEdges.srcArray, myEdges.dstArrayWithType, myEdges.byteArray, edgeSize)
              })

              try {
                bufferLock.writeLock.lock()

                destShard.persistentShardLock.writeLock().lock()


                val destEdges =  timed("destShard.readIntoBuffer", {
                  destShard.readIntoBuffer(destShard.myInterval)
                })

                val totalEdges = myEdges.numEdges + destEdges.numEdges
                this.synchronized {
                  totalMergedEdges +=  myEdges.numEdges
                }
                val combinedSrc = new Array[Long](totalEdges.toInt)
                val combinedDstWithType = new Array[Long](totalEdges.toInt)
                val combinedValues = new Array[Byte](totalEdges.toInt * edgeSize)

                timed("buffermerge-sort", {
                  Sorting.mergeWithValues(myEdges.srcArray, myEdges.dstArrayWithType, myEdges.byteArray,
                    destEdges.srcArray, destEdges.dstArrayWithType, destEdges.byteArray,
                    combinedSrc, combinedDstWithType, combinedValues, edgeSize)
                })


                log("Merging buffer %d -> %d (%d buffered edges, %d from old)".format(bufferShardId, destShard.shardId,
                  myEdges.numEdges, destEdges.numEdges))

                // Write shard
                timed("buffermerge-writeshard", {
                  FastSharder.writeAdjacencyShard(baseFilename, destShard.shardId, numShards, edgeSize, combinedSrc,
                    combinedDstWithType, combinedValues, destShard.myInterval.getFirstVertex,
                    destShard.myInterval.getLastVertex, true)

                })


                // TODO: consider synchronization
                // Write data columns, i.e replace the column shard with new data
                timed("buffermerge-createcols", {
                  (0 until columns(edgeIndexing).size).foreach(columnIdx => {
                    val columnBuffer = ByteBuffer.allocate(totalEdges.toInt * edgeEncoderDecoder.columnLength(columnIdx))
                    EdgeBuffer.projectColumnToBuffer(columnIdx, columnBuffer, edgeEncoderDecoder, combinedValues, totalEdges.toInt)
                    columns(edgeIndexing)(columnIdx)._2.recreateWithData(destShard.shardId, columnBuffer.array())
                  })
                })
                timed("buffermerge-reset", {
                  destShard.reset
                })
                log("Remove from oldBuffers: %d/%d %s".format(bufferShardId, parentIdx, parentIntervals(parentIdx)))
                // Remove the edges from the buffer since they are now assumed to be in the persistent shard
                oldBuffers = oldBuffers.patch(parentIdx, IndexedSeq(intervals.map(interval => EdgeBufferAndInterval(new EdgeBuffer(edgeEncoderDecoder,
                  bufferId=edgeBufferId(bufferShardId, parentIdx, interval.getId)), interval))), 1)

              } catch {
                case e:Exception => e.printStackTrace()
              } finally {
                bufferLock.writeLock().unlock()

                destShard.persistentShardLock.writeLock().unlock()
              }
            }
            )
            if (totalMergedEdges != numEdgesToMerge) {
              throw new IllegalStateException("Mismatch in merging: %d != %d".format(numEdgesToMerge, totalMergedEdges))
            }

          } catch {
            case e : Exception => throw new RuntimeException(e)
          }

        })
        /* Check if upstream shards too big - not in parallel to limit memory consumption */
        async  {
          parentShards.foreach(destShard => destShard.checkSize)
        }
        assert(oldBuffers.size == buffers.size)
        assert(oldBuffers.map(_.map(_.buffer.numEdges).sum).sum == 0)
      }
    }
  }


  //def commitAllToDisk = shards.foreach(_.mergeBuffers())

  val numBufferShards = 4

  def createShards(numShards: Int, idStart: Int, upperLevel: Seq[DiskShard]) : Seq[DiskShard] = {
    val levelIntervals = VertexInterval.createIntervals(intervals.last.getLastVertex, numShards).toIndexedSeq
    (0 until numShards).map(i => new DiskShard(i, i + idStart, levelIntervals,
      upperLevel.filter(_.myInterval.intersects(levelIntervals(i))))).toIndexedSeq
  }

  // Create a tree of shards... think about more elegant way
  val shardSizes = List(256, 64, 16)
  val shardIdStarts = shardSizes.scan(0)(_+_)
  val shardTree =  {
    (0 until shardSizes.size).foldLeft(Seq[Seq[DiskShard]]())((tree : Seq[Seq[DiskShard]], treeLevel: Int) => {
      tree :+ createShards(shardSizes(treeLevel), shardIdStarts(treeLevel), tree.lastOption.getOrElse(Seq[DiskShard]()))
    })
  }

  val shards = shardTree.flatten.toIndexedSeq


  val bufferIntervals = VertexInterval.createIntervals(intervals.last.getLastVertex, 4)
  val bufferShards = (0 until numBufferShards).map(i => new BufferShard(i, bufferIntervals(i),
    shardTree.last.filter(s => s.myInterval.intersects(bufferIntervals(i)))))



  def initialize() : Unit = {
    bufferShards.foreach(_.init())
    initialized = true
  }

  def shardForEdge(src: Long, dst: Long) = {
    // TODO: handle case where the current intervals don't cover the new id

    shards(intervalContaining(dst).get.getId)
  }

  /* For columns associated with vertices */
  val vertexIndexing : DatabaseIndexing = new DatabaseIndexing {
    def nShards = numShards
    def shardForIndex(idx: Long) =
      intervals((idx / intervalLength).toInt).getId
    def shardSize(idx: Int) = scala.math.max(0, 1 + intervalMaxVertexId(idx) - intervals(idx).getFirstVertex)

    def globalToLocal(idx: Long) = {
      val interval = intervals(shardForIndex(idx))
      idx - interval.getFirstVertex
    }
    override def allowAutoExpansion: Boolean = true  // Is this the right place?
    override def name = "vertex"
  }

  /* For columns associated with edges */
  val edgeIndexing : DatabaseIndexing = new DatabaseIndexing {
    def shardForIndex(idx: Long) = PointerUtil.decodeShardNum(idx)
    def shardSize(idx: Int) = shards(idx).numEdges
    def nShards = shards.size
    def globalToLocal(idx: Long) = PointerUtil.decodeShardPos(idx)
    override def name = "edge"

  }

  var columns = scala.collection.mutable.Map[DatabaseIndexing, Seq[(String, Column[Any])]](
    vertexIndexing -> Seq[(String, Column[Any])](),
    edgeIndexing ->  Seq[(String, Column[Any])]()
  )



  /* Columns */
  def createCategoricalColumn(name: String, values: IndexedSeq[String], indexing: DatabaseIndexing) = {
    this.synchronized {
      val col =  new CategoricalColumn(columns(indexing).size, filePrefix=baseFilename + "_COLUMN_cat_" + indexing.name + "_" + name.toLowerCase,
        indexing, values)

      columns(indexing) = columns(indexing) :+ (name, col.asInstanceOf[Column[Any]])
      col
    }
  }

  def createFloatColumn(name: String, indexing: DatabaseIndexing) = {
    this.synchronized {
      val col = new FileColumn[Float](columns(indexing).size, filePrefix=baseFilename + "_COLUMN_float_" +  indexing.name + "_" + name.toLowerCase,
        sparse=false, _indexing=indexing, converter = ByteConverters.FloatByteConverter)
      columns(indexing) = columns(indexing) :+ (name, col.asInstanceOf[Column[Any]])
      col
    }
  }

  def createIntegerColumn(name: String, indexing: DatabaseIndexing) = {
    this.synchronized {
      val col = new FileColumn[Int](columns(indexing).size, filePrefix=baseFilename + "_COLUMN_int_" +  indexing.name + "_" + name.toLowerCase,
        sparse=false, _indexing=indexing, converter = ByteConverters.IntByteConverter)
      columns(indexing) = columns(indexing) :+ (name, col.asInstanceOf[Column[Any]])
      col
    }
  }

  def createShortColumn(name: String, indexing: DatabaseIndexing) = {
    this.synchronized {
      val col = new FileColumn[Short](columns(indexing).size, filePrefix=baseFilename + "_COLUMN_short_" +  indexing.name + "_" + name.toLowerCase,
        sparse=false, _indexing=indexing, converter = ByteConverters.ShortByteConverter)
      columns(indexing) = columns(indexing) :+ (name, col.asInstanceOf[Column[Any]])
      col
    }
  }

  def createByteColumn(name: String, indexing: DatabaseIndexing) = {
    this.synchronized {
      val col = new FileColumn[Byte](columns(indexing).size, filePrefix=baseFilename + "_COLUMN_byte_" +  indexing.name + "_" + name.toLowerCase,
        sparse=false, _indexing=indexing, converter = ByteConverters.ByteByteConverter)
      columns(indexing) = columns(indexing) :+ (name, col.asInstanceOf[Column[Any]])
      col
    }
  }

  def createLongColumn(name: String, indexing: DatabaseIndexing) = {
    this.synchronized {
      val col = new FileColumn[Long](columns(indexing).size, filePrefix=baseFilename + "_COLUMN_long_" +  indexing.name + "_" + name.toLowerCase,
        sparse=false, _indexing=indexing, converter = ByteConverters.LongByteConverter)
      columns(indexing) = columns(indexing) :+ (name, col.asInstanceOf[Column[Any]])
      col
    }
  }

  def createVarDataColumn(name: String, indexing: DatabaseIndexing, blobType: String) : VarDataColumn = {
    this.synchronized {
      val pointerColumn = createLongColumn(name, indexing)
      new VarDataColumn(name, baseFilename, pointerColumn, blobType)
    }
  }

  def createMySQLColumn(tableName: String, columnName: String, indexing: DatabaseIndexing) = {
    val col = new MySQLBackedColumn[String](columns(indexing).size, tableName, columnName, indexing, vertexIdTranslate)
    columns(indexing) = columns(indexing) :+ (tableName + "." + columnName, col.asInstanceOf[Column[Any]])
    col
  }

  def column(name: String, indexing: DatabaseIndexing) = {
    val col = columns(indexing).find(_._1 == name)
    if (col.isDefined) {
      Some(col.get._2)
    } else {
      None
    }
  }


  /* Adding edges */
  // TODO: bulk version
  val counter = new AtomicLong(0)
  val pendingBufferFlushes = new AtomicInteger(0)


  val bufferIntervalLength = bufferShards(0).myInterval.length()

  def bufferForEdge(src:Long, dst:Long) : BufferShard = {
    bufferShards((dst / bufferIntervalLength).toInt)
  }

  def totalBufferedEdges = bufferShards.map(_.numEdgesInclDeletions).sum

  def addEdge(edgeType: Byte, src: Long, dst: Long, values: Any*) : Unit = {
    if (!initialized) throw new IllegalStateException("You need to initialize first!")

    if ((edgeType & 0xf0) != 0) {
      throw new IllegalArgumentException("Only 4 bits allowed for edge type!");
    }

    /* Record keeping */
    this.synchronized {
      updateVertexRecords(src)
      updateVertexRecords(dst)

      incrementInDegree(dst)
      incrementOutDegree(src)

      bufferForEdge(src, dst).addEdge(edgeType, src, dst, values:_*)
    }

    /* Buffer flushing. TODO: make smarter. */

    if (counter.incrementAndGet() % 100000 == 0) {
      if (totalBufferedEdges > bufferLimit * 0.9) {
        if (pendingBufferFlushes.get() > 0) {
          if (totalBufferedEdges < bufferLimit) {
            return
          }
        }
        while(pendingBufferFlushes.get() > 0 && totalBufferedEdges > bufferLimit * 0.9) {
          log("Waiting for pending flush")
          Thread.sleep(200)
        }

        /* TODO: rethink... */
        if (totalBufferedEdges > bufferLimit * 0.5) {
          pendingBufferFlushes.incrementAndGet()
          async {
            val maxBuffer = bufferShards.maxBy(_.numEdgesInclDeletions)
            if (maxBuffer.numEdges > bufferLimit / bufferShards.size / 2) {
              maxBuffer.mergeToParentsAndClear()
            }

            pendingBufferFlushes.decrementAndGet()
          }
        } else {
          log("Already drained enough ...");
        }
      }

    }
  }

  def addEdgeOrigId(edgeType: Byte, src:Long, dst:Long, values: Any*) {
    addEdge(edgeType, originalToInternalId(src), originalToInternalId(dst), values:_*)
  }

  // TODO: remove redundancy with updateEdge
  def findEdgePointer(edgeType: Byte, src: Long, dst: Long)(updateFunc: Option[Long] => Unit) = {
    this.synchronized {
      val ptrOpt = {
        val bufferShard = bufferForEdge(src, dst)
        val bufferOpt = bufferShard.find(edgeType, src, dst)
        bufferOpt.orElse( {
          // Look first the most recent data, so reverse
          val persistentPtrOpt = shards.filter(_.myInterval.contains(dst)).reverseIterator.map(_.find(edgeType, src, dst)).find(_.isDefined)
          persistentPtrOpt.get
        })
      }
      updateFunc(ptrOpt)
    }
  }

  /**
   * Updates edge value for given column. Returns true if operation succeeds (edge is found).
   * @param src
   * @param dst
   * @param column
   * @param newValue
   * @tparam T
   * @return
   */
  def updateEdge[T](edgeType: Byte, src: Long, dst: Long, column: Column[T], newValue: T) : Boolean = {
    /* First buffers */
    val bufferShard = bufferForEdge(src, dst)
    if (!bufferShard.update(edgeType, src, dst, column.columnId, newValue)) {
      /* And then persistent shard */
      val possibleShards = shards.filter(_.myInterval.contains(dst)).reverse // Look first the most recent data, so reverse
      possibleShards.foreach( shard => {
        shard.persistentShardLock.writeLock().lock()
        try {
          val idx = shard.persistentShard.find(edgeType, src, dst)
          if (idx != null) {
            column.set(idx, newValue)
            return true
          }
        } finally {
          shard.persistentShardLock.writeLock().unlock()
        }
      })
      false
    } else {
      true
    }
  }


  def updateEdgeOrigId[T](edgeType: Byte, src: Long, dst: Long, column: Column[T], newValue: T) : Boolean = {
    updateEdge(edgeType, originalToInternalId(src), originalToInternalId(dst), column, newValue)
  }


  def deleteEdge(edgeType: Byte, src: Long, dst: Long) : Boolean = {
    /* First buffers */

    val bufferShard = bufferForEdge(src, dst)
    if (!bufferShard.deleteEdge(edgeType, src, dst)) {
      /* And then persistent shard */
      val possibleShards = shards.filter(_.myInterval.contains(dst)).reverse // Look first the most recent data, so reverse
      possibleShards.foreach( shard => {
        shard.persistentShardLock.writeLock().lock()
        try {
          if (shard.persistentShard.deleteEdge(edgeType, src, dst)) {
            decrementInDegree(dst)
            decrementOutDegree(src)
            return true
          }
        } finally {
          shard.persistentShardLock.writeLock().unlock()
        }
      })
      false
    } else {
      decrementInDegree(dst)
      decrementOutDegree(src)
      true
    }
  }

  def deleteEdgeOrigId(edgeType: Byte, src: Long, dst: Long) : Boolean = deleteEdge(edgeType, originalToInternalId(src), originalToInternalId(dst))

  def deleteVertex(internalId: Long) : Boolean = {
    this.synchronized {
      val (inDeg, outDeg) =
        (outDegree(internalId), inDegree(internalId))

      if (inDeg + outDeg > 0) {

        // Need to pass all shards!
        bufferShards.par.foreach(bufferShard => bufferShard.deleteAllEdgesForVertex(internalId, inDeg > 0, outDeg > 0))

        shards.reverse.par.foreach(shard => {  // Reverse order to maintain same locking direction
          shard.persistentShardLock.writeLock().lock()
          try {
            shard.persistentShard.deleteAllEdgesFor(internalId, inDeg > 0, outDeg > 0)
          } finally {
            shard.persistentShardLock.writeLock().unlock()
          }
        })

        degreeColumn.set(internalId, 0L)  // Zero degree
        true
      } else {
        false
      }
    }
  }

  def deleteVertexOrigId(vertexId: Long): Boolean = { deleteVertex(originalToInternalId(vertexId)) }


  def setByPointer[T](column: Column[T], ptr: Long, value: T) = {
    if (PointerUtil.isBufferPointer(ptr)) {
      val bufferId = PointerUtil.decodeBufferNum(ptr)
      val bufferIdx = PointerUtil.decodeBufferPos(ptr)
      val bufferRef = bufferReference(bufferId)
      bufferShards(bufferRef.bufferShardId).nthBuffer(bufferRef.nthBuffer).buffer.setColumnValue(bufferIdx, column.columnId, value)
    } else {
      column.set(ptr, value)
    }
  }
  def getByPointer[T](column: Column[T], ptr: Long, buf: ByteBuffer) : Option[T] = {
    assert(column.indexing == edgeIndexing)

    if (PointerUtil.isBufferPointer(ptr)) {
         val bufferId = PointerUtil.decodeBufferNum(ptr)
         val bufferIdx = PointerUtil.decodeBufferPos(ptr)
         val bufferRef = bufferReference(bufferId)
         // NOTE: buffer reference may be invalid!!! TODO

         buf.rewind
         bufferShards(bufferRef.bufferShardId).nthBuffer(bufferRef.nthBuffer).buffer.readEdgeIntoBuffer(bufferIdx, buf)
         // TODO: read only necessary column

         buf.rewind
         val vals = edgeEncoderDecoder.decode(buf, -1, -1)
         Some(vals.values(column.columnId).asInstanceOf[T])
       } else {
          column.get(ptr)
       }
  }
  def getByPointer[T](column: Column[T], ptr: Long) : Option[T] = {
     assert(column.indexing == edgeIndexing)
     getByPointer(column, ptr, ByteBuffer.allocate(edgeEncoderDecoder.edgeSize))
  }

  def getEdgeValue[T](edgeType: Byte, src: Long, dst: Long, column: Column[T]) : Option[T] = {
    val bufferShard = bufferForEdge(src, dst)
    val bufferOpt = bufferShard.getValue(edgeType, src, dst, column.columnId)
    bufferOpt.orElse( {
      // Look first the most recent data, so reverse
      val idxOpt = shards.filter(_.myInterval.contains(dst)).reverseIterator.map(_.find(edgeType, src, dst))
        .find(_.isDefined)
      idxOpt.map(idx => column.get(idx.get)).headOption.getOrElse(None)
    })
  }

  def getEdgeValueOrigId[T](edgeType: Byte, src: Long, dst: Long, column: Column[T]) : Option[T] = {
    getEdgeValue(edgeType, originalToInternalId(src), originalToInternalId(dst), column)
  }

  /* Vertex id conversions */
  def originalToInternalId(vertexId: Long) = vertexIdTranslate.forward(vertexId)
  def internalToOriginalId(vertexId: Long) = vertexIdTranslate.backward(vertexId)

  def numVertices = intervals.zip(intervalMaxVertexId).map(z => z._2 - z._1.getFirstVertex + 1).sum
  def numEdges = shards.map(_.numEdges).sum + bufferShards.map(_.numEdges).sum

  /* Column value lookups */
  def edgeColumnValues[T](column: Column[T], pointers: Set[java.lang.Long]) : Map[java.lang.Long, Option[T]] = {
    val persistentPointers = pointers.filter(ptr => !PointerUtil.isBufferPointer(ptr))
    val bufferPointers = pointers.filter(ptr => PointerUtil.isBufferPointer(ptr))

    val columnIdx = column.columnId
    assert(columnIdx >= 0)
    val persistentResults = column.getMany(persistentPointers)
    val buf = ByteBuffer.allocate(edgeEncoderDecoder.edgeSize)

    val bufferResults = bufferPointers.map(ptr => {
       ptr -> getByPointer(column, ptr, buf)
    }).toMap
    println("Retrieved %d buffer results, %d persistent", bufferPointers.size, persistentPointers.size)
    persistentResults ++ bufferResults
  }

  /* Queries */
  def queryIn(internalId: Long, edgeType: Byte) = {
    if (!initialized) throw new IllegalStateException("You need to initialize first!")
    timed ("query-in", {

      val result = new QueryResultContainer(Set(internalId))


      log("In-query in: %d".format(internalId))
      /* Look for buffers (in parallel, of course) -- TODO: profile if really a good idea */
      bufferShards.filter(_.myInterval.contains(internalId)).foreach( bufferShard => {
        bufferShard.bufferLock.readLock().lock()
        try {
          bufferShard.buffersForDstQuery(internalId).par.foreach(
            buf => {
              try {
                buf.buffer.findInNeighborsCallback(internalId, result, edgeType)
              } catch {
                case e: Exception => e.printStackTrace()
              }
            }
          )
        } finally {
          bufferShard.bufferLock.readLock().unlock()
        }
      })

      /* Look for persistent shards */
      val targetShards = shards.filter(_.myInterval.contains(internalId))
      targetShards.par.foreach(shard => {
        shard.persistentShardLock.readLock().lock()
        try {
          try {
            shard.persistentShard.queryIn(internalId, result, edgeType)
          } catch {
            case e: Exception => e.printStackTrace()
          }
        } finally {
          shard.persistentShardLock.readLock().unlock()
        }
      })
      log("Total in-results: %d,  : %d".format(result.combinedResults().size, result.resultsFor(internalId).size))

      new QueryResult(edgeIndexing, result.resultsFor(internalId), this)
    } )
  }


  def queryOut(internalId: Long, edgeType: Byte) = {
    if (!initialized) throw new IllegalStateException("You need to initialize first!")

    timed ("query-out", {
      val res =  queryOutMultiple(Set[java.lang.Long](internalId), edgeType)
      // Note, change indexing
      res.withIndexing(edgeIndexing)
    } )
  }


  // TODO: query needs to acquire ALL locks before doing query -- AVOID OR DETECT DEADLOCKS!
  def queryOutMultiple(javaQueryIds: Set[java.lang.Long], edgeType: Byte)  = {
    if (!initialized) throw new IllegalStateException("You need to initialize first!")

    timed ("query-out-multiple", {
      val resultContainer =  new QueryResultContainer(javaQueryIds)


      timed("query-out-buffers", {
        bufferShards.par.foreach(bufferShard => {
          /* Look for buffers */
          bufferShard.bufferLock.readLock().lock()
          try {
            javaQueryIds.par.foreach(internalId =>
              bufferShard.buffersForSrcQuery(internalId).foreach(buf => {
                buf.buffer.findOutNeighborsCallback(internalId, resultContainer, edgeType)
                if (!buf.interval.contains(internalId))
                  throw new IllegalStateException("Buffer interval %s did not contain %s".format(buf.interval, internalId))
              }))
          } catch {
            case e: Exception  => {
              e.printStackTrace()
            }
          } finally {
            bufferShard.bufferLock.readLock().unlock()
          }
        })
      })


      val atc = new AtomicInteger(0)
      timed("query-out-persistent", {
        // TODO: fix this java-scala long mapping
        shards.par.foreach(shard => {
          try {
            atc.incrementAndGet()
            shard.persistentShardLock.readLock().lock()
            try {
              shard.persistentShard.queryOut(javaQueryIds, resultContainer, edgeType)
            } finally {
              shard.persistentShardLock.readLock().unlock()
            }

            val alive = atc.getAndDecrement
          } catch {
            case e: Exception  => {
              e.printStackTrace()
            }
          }
        })
      })
      log("Out query finished")

      timed("query-out-combine", {
        new QueryResult(vertexIndexing, resultContainer.combinedResults(), this)
      })
    } )
  }
  def queryOutMultiple(internalIds: Seq[Long], edgeType: Byte) : QueryResult = queryOutMultiple(internalIds.map(_.asInstanceOf[java.lang.Long]).toSet, edgeType)

  /**
   * High-performance reusable object for encoding edges into bytes
   */
  def edgeEncoderDecoder = {
    val encoderSeq =  columns(edgeIndexing).map(m => (x: Any, bb: ByteBuffer) => m._2.encode(x, bb))
    val decoderSeq =  columns(edgeIndexing).map(m => (bb: ByteBuffer) => m._2.decode(bb))

    val columnLengths = columns(edgeIndexing).map(_._2.elementSize).toIndexedSeq
    val columnOffsets = columnLengths.scan(0)(_+_).toIndexedSeq
    val _edgeSize = columns(edgeIndexing).map(_._2.elementSize).sum
    val idxRange = 0 until encoderSeq.size

    new EdgeEncoderDecoder {
      // Encodes an edge and its values to a byte buffer. Note: all values must be present
      def encode(out: ByteBuffer, values: Any*) = {
        if (values.size != idxRange.size)
          throw new IllegalArgumentException("Number of inputs must match the encoder configuration: %d != given %d".format(idxRange.size, values.size))
        idxRange.foreach(i => {
          encoderSeq(i)(values(i), out)
        })
        _edgeSize
      }

      def decode(buf: ByteBuffer, src: Long, dst: Long) = DecodedEdge(src, dst, decoderSeq.map(dec => dec(buf)))

      def readIthColumn(buf: ByteBuffer, columnIdx: Int, out: ByteBuffer, workArray: Array[Byte]) = {
        buf.position(buf.position() + columnOffsets(columnIdx))
        val l = columnLengths(columnIdx)
        buf.get(workArray, 0, l)
        out.put(workArray, 0, l)
      }


      // Note, buffer position has to be set to a beginning of row
      def setIthColumnInBuffer[T](buf: ByteBuffer, columnIdx: Int, value: T) = {
        buf.position(buf.position() + columnOffsets(columnIdx))
        val l = columnLengths(columnIdx)
        encoderSeq(columnIdx)(value, buf)
      }

      def edgeSize = _edgeSize
      def columnLength(columnIdx: Int) = columnLengths(columnIdx)
    }
  }


  /* Degree management. Hi bytes = in-degree, lo bytes = out-degree */
  val degreeColumn = createLongColumn("degree", vertexIndexing)

  /* Initialize max vertex id by looking at the degree columns */
  val intervalMaxVertexId =  degreeColumn.blocks.zip(intervals).map {
    case (block, interval) => block.size + interval.getFirstVertex
  }.toSeq.toArray[Long]


  // Manage shard boundaries etc.
  def updateVertexRecords(internalId: Long): Unit = {
    val shardIdx = (internalId / intervals(0).length()).toInt
    if (intervalMaxVertexId(shardIdx) < internalId) { intervalMaxVertexId(shardIdx) = internalId }
  }
  // Premature optimization
  val degreeEncodingBuffer = ByteBuffer.allocate(8)

  def incrementInDegree(internalId: Long) : Unit =
    degreeColumn.update(internalId, curOpt => {
      val curValue = curOpt.getOrElse(0L)
      Util.setHi(Util.hiBytes(curValue) + 1, curValue) }, degreeEncodingBuffer)

  def incrementOutDegree(internalId: Long) : Unit =
    degreeColumn.update(internalId, curOpt => {
      val curValue = curOpt.getOrElse(0L)
      Util.setLo(Util.loBytes(curValue) + 1, curValue) }, degreeEncodingBuffer)

  def decrementInDegree(internalId: Long) : Unit =
    degreeColumn.update(internalId, curOpt => {
      val curValue = curOpt.getOrElse(1L)
      Util.setHi(Util.hiBytes(curValue) - 1, curValue) }, degreeEncodingBuffer)

  def decrementOutDegree(internalId: Long) : Unit =
    degreeColumn.update(internalId, curOpt => {
      val curValue = curOpt.getOrElse(1L)
      Util.setLo(Util.loBytes(curValue) - 1, curValue) }, degreeEncodingBuffer)


  def inDegree(internalId: Long) = Util.hiBytes(degreeColumn.get(internalId).getOrElse(0L))
  def outDegree(internalId: Long) = Util.loBytes(degreeColumn.get(internalId).getOrElse(0L))


  def joinValue[T1](col: Column[T1], vertexId: Long, idx: Int, shardId: Int=0, buffer: Option[EdgeBuffer] = None): T1 = {
    (col.indexing match {
      case `vertexIndexing` => col.get(vertexId)
      case `edgeIndexing` => {
        buffer match {
          case None => col.get(PointerUtil.encodePointer(shardId, idx))
          case Some(buf) => throw new NotImplementedException
        }
      }
      case _ => throw new UnsupportedOperationException
    }).get
  }

  /** Computational functionality **/
  def sweepInEdgesWithJoin[T1, T2](intervalId: Int, maxVertex: Long, col1: Column[T1], col2: Column[T2])(updateFunc: (Long, Long, T1, T2) => Unit) = {
    val interval = intervals(intervalId)
    val shardsToSweep = shards.filter(shard => shard.myInterval.intersects(interval))

    shardsToSweep.foreach(shard => {
      shard.persistentShardLock.readLock().lock()
      try {
        val edgeIterator = shard.persistentShard.edgeIterator()
        var idx = 0
        while(edgeIterator.hasNext) {
          edgeIterator.next()
          val (src, dst) = (edgeIterator.getSrc, edgeIterator.getDst)
          if (interval.contains(dst) && dst <= maxVertex) {
            val v1 : T1 = joinValue[T1](col1,  edgeIterator.getSrc, idx, shard.shardId)
            val v2 : T2 = joinValue[T2](col2,  edgeIterator.getSrc, idx, shard.shardId)
            updateFunc(src, dst, v1, v2)
          }
          idx += 1
        }
      } finally {
        shard.persistentShardLock.readLock().unlock()
      }
    })

    val bufferToSweep = bufferShards.find(_.myInterval.intersects(interval)).get
    bufferToSweep.bufferLock.readLock().lock()
    try {
      var matches = 0 // debug
      bufferToSweep.buffersForDstQuery(interval.getFirstVertex).foreach(buf => {
        val edgeIterator = buf.buffer.edgeIterator
        var idx = 0
        while(edgeIterator.hasNext) {
          edgeIterator.next()
          val (src, dst) = (edgeIterator.getSrc, edgeIterator.getDst)
          if (interval.contains(dst)  && dst <= maxVertex) {  // The latter comparison is bit ackward
          val v1 : T1 = joinValue[T1](col1,  edgeIterator.getSrc, idx, buffer=Some(buf.buffer))
            val v2 : T2 = joinValue[T2](col2,  edgeIterator.getSrc, idx, buffer=Some(buf.buffer))
            updateFunc(src, dst, v1, v2)
            matches += 1
          }
          idx += 1
        }
      })
    } finally {
      bufferToSweep.bufferLock.readLock().unlock()
    }
  }

  var activeComputations = Set[Computation]()

  def runIteration(computation: Computation, continuous: Boolean = false) = {
    if (activeComputations.contains(computation)) {
      println("Computation %s was already active!".format(computation))
    } else {
      activeComputations = activeComputations + computation
      async {
        var iter = 0
        try {
          do {
            timed("runiteration_%s_%d".format(computation, iter), {
              intervals.foreach(int => {
                computation.computeForInterval(int.getId, int.getFirstVertex, intervalMaxVertexId(int.getId))
              } )
            })
            iter += 1
          } while (continuous)
        } finally {
          activeComputations = activeComputations - computation
        }
      }
    }
  }
}



trait DatabaseIndexing {
  def nShards : Int
  def name: String
  def shardForIndex(idx: Long) : Int
  def shardSize(shardIdx: Int) : Long
  def globalToLocal(idx: Long) : Long
  def allowAutoExpansion: Boolean = false  // Is this the right place?
}

/**
 * Encodes edge values to a byte array. These are used for high-performance
 * inserts.
 */
trait EdgeEncoderDecoder {

  // Encodes an edge and its values to a byte buffer. Note: all values must be present
  def encode(out: ByteBuffer, values: Any*) : Int

  def decode(buf: ByteBuffer, src: Long, dst: Long) : DecodedEdge

  def edgeSize: Int

  // For making fast projections. Writes ith column to out
  def readIthColumn(buf: ByteBuffer, columnIdx: Int, out: ByteBuffer, workArray: Array[Byte])

  // Note, buffer has to be set to a beginning of row
  def setIthColumnInBuffer[T](buf: ByteBuffer, columnIdx: Int, value: T)

  def columnLength(columnIdx: Int) : Int
}

case class DecodedEdge(src: Long, dst:Long, values: Seq[Any])
package edu.cmu.akyrola.experiments

import edu.cmu.graphchidb.{GraphChiDatabase, GraphChiDatabaseAdmin}
import scala.io.Source
import java.io.{FileWriter, File}
import edu.cmu.graphchidb.Util._
import scala.util.Random
import edu.cmu.graphchidb.compute.Pagerank
import edu.cmu.graphchi.GraphChiEnvironment
import edu.cmu.graphchidb.queries.Queries
import edu.cmu.graphchidb.queries.internal.{SimpleArrayReceiver, SimpleSetReceiver}
import edu.cmu.graphchi.queries.QueryCallback
import java.net._
import java.text._
import java.{lang, util}
import java.util.Date

/**
 * Ingest a full live journal graph from scratch
 * @author Aapo Kyrola
 */
object LiveJournalExp {

  /**
   *
  import edu.cmu.akyrola.prototype.LiveJournalAccess._
  import edu.cmu.graphchidb.compute.Pagerank

  val pagerankComputation = new Pagerank(DB)
     DB.runIteration(pagerankComputation)
   */

  val source =  "/Users/akyrola/graphs/soc-LiveJournal1.txt"

  val baseFilename = "/Users/akyrola/graphs/DB/livejournal/livejournal.txt"

  val sdf = new java.text.SimpleDateFormat("YYYYMMDD_HHmmss")


  lazy val DB = new GraphChiDatabase(baseFilename, enableVertexShardBits=false, numShards = 16)

  /* Create columns */
  /* val timestampColumn = DB.createIntegerColumn("timestamp", DB.edgeIndexing)
   val typeColumn = DB.createCategoricalColumn("type",  IndexedSeq("follow", "like"), DB.edgeIndexing)
    */

  val pagerankComputation = new Pagerank(DB)
  val pagerankCol = DB.column("pagerank", DB.vertexIndexing).get




  def startIngest() {
    DB.initialize()

    var i = 0
    val r = new Random
    val t = System.currentTimeMillis()
    timed("ingest", {
      val ingestMeter = GraphChiEnvironment.metrics.meter("edgeingest")

      Source.fromFile(new File(source)).getLines().foreach( ln => {
        if (!ln.startsWith("#")) {
          val toks = ln.split("\t")
          val from = Integer.parseInt(toks(0))
          val to = Integer.parseInt(toks(1))
          val edgeType = if ((from + to) % 3 == 0) "follow" else "like"
          DB.addEdgeOrigId(0, from, to) /*, (from + to),
              typeColumn.indexForName(edgeType)) */
          i += 1
          if (i % 1000 == 0) ingestMeter.mark(1000)
          if (i % 1000000 == 0) println((System.currentTimeMillis - t) / 1000 + " s. : Processed: %d".format(i) + " ;" + ingestMeter.getOneMinuteRate + " / sec"
            + "; mean=" + ingestMeter.getMeanRate + " edges/sec")
        }
      })
    })
  }


  class BitSetOrigIdReceiver(outEdges: Boolean) extends QueryCallback {
    val bitset = new util.BitSet(100000000)
    def immediateReceive() = true
    def receiveEdge(src: Long, dst: Long, edgeType: Byte, dataPtr: Long) = {
      if (outEdges)   bitset.set(DB.internalToOriginalId(dst).toInt)
      else bitset.set(DB.internalToOriginalId(src).toInt)
    }

    def receiveInNeighbors(vertexId: Long, neighborIds: util.ArrayList[lang.Long], edgeTypes: util.ArrayList[lang.Byte], dataPointers: util.ArrayList[lang.Long])= throw new IllegalStateException()
    def receiveOutNeighbors(vertexId: Long, neighborIds: util.ArrayList[lang.Long], edgeTypes: util.ArrayList[lang.Byte], dataPointers: util.ArrayList[lang.Long])= throw new IllegalStateException()

    def size = bitset.cardinality()
  }
  def fofTest(n: Int, pagerank:Boolean, limit: Int = 200): Unit = {
    DB.initialize()

    var i = 1
    val t = System.currentTimeMillis()
    val r = new java.util.Random(260379)

    if (pagerank) {
      DB.runIteration(pagerankComputation, continuous=true)
    }

    val id = "%s_%s_i%d_%s".format(InetAddress.getLocalHost.getHostName.substring(0,8), sdf.format(new Date()), n,
      if (pagerank) { "pagerank" } else {""})


    val foflog = new FileWriter("fof_livejournal_%s_limit_%d.csv".format(id, limit))


    foflog.write("count,micros\n")

    while(i < n) {
      val v = math.abs(r.nextLong() % 4500000) + 1
      val a = new BitSetOrigIdReceiver(outEdges = true)

      val friendReceiver = new SimpleArrayReceiver(outEdges = true, limit=limit)
      val st = System.nanoTime()
      DB.queryOut(DB.originalToInternalId(v), 0, friendReceiver)
      val st2 = System.nanoTime()
      DB.queryOutMultiple(friendReceiver.arr, 0.toByte, a)
      val tFof = System.nanoTime() - st
      val cnt = a.size
      if (i % 1000 == 0 && cnt >= 0) {
        printf("%d %d fof:%d\n".format(System.currentTimeMillis() - t, i, cnt))
      }
      i += 1
      if (cnt > 0)  {
        // println("%d,%f,%f, %d\n".format(cnt, tFof * 0.001, (st2 - st) * 0.001, v))
        foflog.write("%d,%f,%d\n".format(cnt, tFof * 0.001,v))
      }
      foflog.flush()
    }

    foflog.close()
    println("Finished")
    System.exit(0)

  }

  def shortestPathTest(n: Int) = {
    DB.initialize()

    val t = System.currentTimeMillis()
    val r = new java.util.Random(260379)

    var foundTimes = 0L

    (0 until n).foreach(i => {
      val from = math.abs(r.nextLong() % 4500000) + 1
      val to = math.abs(r.nextLong() % 4500000) + 1
      val st = System.nanoTime()
      val path = Queries.shortestPath(DB.originalToInternalId(from), DB.originalToInternalId(to), edgeType=0, maxDepth = 5)(DB)
      val tt = System.nanoTime() - st
      println("%d,%d,%d micros:%f".format(from, to, path.size -1, tt*0.001))
      if (path.size > 0) foundTimes += tt

    } )

    println("Total time: " + (System.currentTimeMillis() - t) + " ms n=%d; for found ones =%f".format(n, foundTimes * 0.000001))
  }


  def main(args: Array[String]) {
    if (args(0) == "ingest") {
      GraphChiDatabaseAdmin.createDatabase(baseFilename, numShards = 16)
      startIngest
    }
    if (args(0) == "fof") {
      fofTest(args(1).toInt, pagerank=false)
    }
    if (args(0) == "fofpagerank") {
      fofTest(args(1).toInt, pagerank=true)
    }
    if (args(0) == "shortestpath") {
      shortestPathTest(args(1).toInt)
    }

  }

}
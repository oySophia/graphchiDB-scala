package edu.cmu.graphchidb.linkbench

import com.facebook.LinkBench.{Link, Phase, Node, GraphStore}
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import edu.cmu.graphchidb.{GraphChiDatabase, GraphChiDatabaseAdmin}
import edu.cmu.graphchidb.compute.Pagerank
import edu.cmu.graphchidb.storage.{CategoricalColumn, Column}
import sun.reflect.generics.reflectiveObjects.NotImplementedException

/**
 * @author Aapo Kyrola
 */
class GraphChiLinkBenchAdapter extends GraphStore {


  var currentPhase: Phase = null

  var idSequence = new AtomicLong()

  /**** NODE STORE **/

  def close() = {
    println("GraphChiLinkBenchAdapter: close")
  }

  def clearErrors(threadId: Int) = {
    println("GraphChiLinkBenchAdapter: clear errors")

  }


  val baseFilename = "/Users/akyrola/graphs/DB/linkbench/linkbench"
  var DB : GraphChiDatabase = null


  // TODO!
  def edgeType(typeValue: Long) = (typeValue % 255).toByte

  /* Edge columns */
  var edgeTimestamp : Column[Int] = null
  var edgeVersion: Column[Byte]  = null    // Note, only 8 bits
  // payload data
  // var edgePayLoad: ....

  var nodeTimestamp : Column[Int]  = null
  var nodeVersion: Column[Byte]   = null

  var type0Counters : Column[Int]  = null
  var type1Counters : Column[Int]  = null
  // payload data
  // var nodePaylaod




  def initialize(p1: Properties, phase: Phase, threadId: Int) = {
    println("Initialize: %s, %s, %d".format(p1, currentPhase, threadId))
    currentPhase =  phase
    currentPhase match {
      case Phase.LOAD => {

      }
      case Phase.REQUEST => {

      }
    }

    DB = new GraphChiDatabase(baseFilename)
    /* Create columns */
    edgeTimestamp = DB.createIntegerColumn("time", DB.edgeIndexing)
    edgeVersion = DB.createByteColumn("vers", DB.edgeIndexing)

    nodeTimestamp = DB.createIntegerColumn("time", DB.vertexIndexing)
    nodeVersion = DB.createByteColumn("vers", DB.vertexIndexing)

    type0Counters = DB.createIntegerColumn("type0cnt", DB.vertexIndexing)
    type1Counters = DB.createIntegerColumn("type1cnt", DB.vertexIndexing)

    DB.initialize()

  }


  /**
   * Reset node storage to a clean state in shard:
   *   deletes all stored nodes
   *   resets id allocation, with new IDs to be allocated starting from startID
   */
  def resetNodeStore(p1: String, startId: Long) : Unit = {
    println("GraphChiLinkBenchAdapter: reset node store, startId: " + startId)
    GraphChiDatabaseAdmin.createDatabase(baseFilename)
    idSequence.set(startId)
  }



  def addNode(databaseId: String, node: Node) : Long = {
    println("Add node %s, %s".format(databaseId, node))
    /* Just insert. Note: nodetype is ignored. */
    val newId = idSequence.getAndIncrement()
    val newInternalId = DB.originalToInternalId(newId)
    DB.updateVertexRecords(newInternalId)
    nodeTimestamp.set(newInternalId, node.time)
    nodeVersion.set(newInternalId, node.version.toByte)
    newId
  }

  def getNode(databaseId: String, nodeType: Int, id: Long) : Node = {
    val internalId = DB.originalToInternalId(id)
    val payloadData = new Array[Byte](0) // TODO!!!
    val timestamp = nodeTimestamp.get(internalId).getOrElse(0)
    if (timestamp > 0) {
      val versionByte = nodeVersion.get(internalId).getOrElse(0.toByte)
      new Node(id, 0, versionByte, timestamp, payloadData)
    } else {
      null
    }
  }

  def updateNode(databaseId: String, node: Node) = {
    println("Update node %s, %s".format(databaseId, node))
    // TODO: payload
    val internalId = DB.originalToInternalId(node.id)
    val timestamp = nodeTimestamp.get(internalId).getOrElse(0)

    if (timestamp > 0) {
      nodeTimestamp.set(internalId, node.time)
      nodeVersion.set(internalId, node.version.toByte)
      true
    } else {
      false
    }
  }

  def deleteNode(databaseId: String, nodeType: Int, id: Long) = {
    println("Delete node: %s %d".format(nodeType, id))
    DB.deleteVertexInternalId(id)
    false
  }

  /**** LINK STORE ****/


  /// NOTE: can apparently ignore noInverse settings!
  def addLink(databaseId: String, edge: Link, noInverse: Boolean) = {
    println("Add link %s, %s".format(edge, noInverse))
    if (currentPhase == Phase.LOAD) {
      /* Just insert */

      val edgeTypeByte = edgeType(edge.link_type)
      DB.addEdgeOrigId(edge.version.toByte, edge.id1, edge.id2, edge.time, edgeTypeByte)

      /* Adjust counters */
      // NOTE: hard-coded only two types
      val countColumn = if (edgeTypeByte == 0) type0Counters else type1Counters
      countColumn.update(DB.originalToInternalId(edge.id1), c => c.getOrElse(0) + 1)
    } else {
      /* Check first if exits, then insert */
      throw new NotImplementedException
    }
   true
}


  /**
   * Delete link identified by parameters from store
   * @param databaseId
   * @param id1
   * @param linkType
   * @param id2
   * @param noInverse
   * @param exPunge if true, delete permanently.  If false, hide instead
   * @return true if row existed. Implementation is optional, for informational
   *         purposes only.
   * @throws Exception
   */
  def deleteLink(databaseId: String, id1: Long, linkType: Long, id2: Long, noInverse: Boolean, exPunge: Boolean) = {
    println("Delete link: %s %s %s expunge: %s".format(id1, linkType, id2, exPunge))
    DB.deleteEdgeOrigId(edgeType(linkType), id1, id2)
    false
  }

  def updateLink(databaseId: String, edge: Link, noInverse: Boolean) = {
    println("Update link: %s".format(edge))
    false
  }

  def getLink(databaseId: String, id1: Long, linkType: Long, id2: Long) : Link = {
    println("Get link: %s, %s, %s".format(id1, linkType, id2))
    null
  }

  def getLinkList(databaseId: String, id1: Long, linkType: Long) : Array[Link]    = {
    println("getLinkList %s %s".format(id1, linkType))
    null
  }

  def getLinkList(databaseId: String, id1: Long, linkType: Long, minTimestamp: Long, maxTimestamp: Long,
                  offset: Int, limit: Int) : Array[Link]   = {
    println("getLinkList-range %s %s %s %s offset: %d, limit: %d".format(id1, linkType, minTimestamp, maxTimestamp,
      offset, limit))
    null
  }

  def countLinks(databaseId: String, id1: Long, linkType: Long) = {
    println("countLInks: %s %s".format(id1, linkType))
    0L
  }
}

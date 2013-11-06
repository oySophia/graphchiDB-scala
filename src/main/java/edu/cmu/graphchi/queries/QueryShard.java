package edu.cmu.graphchi.queries;

import com.codahale.metrics.MetricRegistry;
import static com.codahale.metrics.MetricRegistry.*;
import static com.codahale.metrics.MetricRegistry.name;

import com.codahale.metrics.Timer;
import edu.cmu.graphchi.ChiFilenames;
import edu.cmu.graphchi.GraphChiEnvironment;
import edu.cmu.graphchi.engine.VertexInterval;
import edu.cmu.graphchi.preprocessing.VertexIdTranslate;
import edu.cmu.graphchi.shards.ShardIndex;


import java.io.File;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Shard query support
 * @author Aapo Kyrola
 */
public class QueryShard {

    private ShardIndex index;
    private int shardNum;
    private int numShards;
    private String fileName;
    private File adjFile;

    private LongBuffer adjBuffer;
    private LongBuffer pointerIdxBuffer;
    private IntBuffer inEdgeStartBuffer;
    private VertexInterval interval;


    private static final Timer inEdgeIndexLookupTimer = GraphChiEnvironment.metrics.timer(name(QueryShard.class, "inedge-indexlookup"));

    private final Timer inEdgePhase1Timer = GraphChiEnvironment.metrics.timer(name(QueryShard.class, "inedge-phase1"));
    private final Timer inEdgePhase2Timer = GraphChiEnvironment.metrics.timer(name(QueryShard.class, "inedge-phase2"));


    public QueryShard(String fileName, int shardNum, int numShards) throws IOException {
        this.shardNum = shardNum;
        this.numShards = numShards;
        this.fileName = fileName;

        this.interval = ChiFilenames.loadIntervals(fileName, numShards).get(shardNum);

        adjFile = new File(ChiFilenames.getFilenameShardsAdj(fileName, shardNum, numShards));

        adjBuffer = new java.io.RandomAccessFile(adjFile, "r").getChannel().map(FileChannel.MapMode.READ_ONLY, 0,
                    adjFile.length()).asLongBuffer();

        index = new ShardIndex(adjFile);
        loadPointers();
        loadInEdgeStartBuffer();
    }

    private void loadInEdgeStartBuffer() throws IOException {
        File inEdgeStartBufferFile = new File(ChiFilenames.getFilenameShardsAdjStartIndices(adjFile.getAbsolutePath()));
        FileChannel inEdgeStartChannel = new java.io.RandomAccessFile(inEdgeStartBufferFile, "r").getChannel();
        inEdgeStartBuffer = inEdgeStartChannel.map(FileChannel.MapMode.READ_ONLY, 0, inEdgeStartBufferFile.length()).asIntBuffer();
    }

    void loadPointers() throws IOException {
        File pointerFile = new File(ChiFilenames.getFilenameShardsAdjPointers(adjFile.getAbsolutePath()));
        FileChannel ptrFileChannel = new java.io.RandomAccessFile(pointerFile, "r").getChannel();
        pointerIdxBuffer = ptrFileChannel.map(FileChannel.MapMode.READ_ONLY, 0, pointerFile.length()).asLongBuffer();
    }

    public synchronized void queryOut(Collection<Long> queryIds, QueryCallback callback) {
        try {
             /* Sort the ids because the index-entries will be in same order */
            ArrayList<Long> sortedIds = new ArrayList<Long>(queryIds);
            Collections.sort(sortedIds);

            ArrayList<ShardIndex.IndexEntry> indexEntries = new ArrayList<ShardIndex.IndexEntry>(sortedIds.size());
            for(Long a : sortedIds) {
                indexEntries.add(index.lookup(a));
            }

            ShardIndex.IndexEntry entry = null;
            for(int qIdx=0; qIdx < sortedIds.size(); qIdx++) {
                entry = indexEntries.get(qIdx);
                long vertexId = sortedIds.get(qIdx);

                long curPtr = findIdxAndPos(vertexId, entry);

                if (curPtr != (-1L)) {
                    long nextPtr = pointerIdxBuffer.get();
                    int n = (int) (VertexIdTranslate.getAux(nextPtr) - VertexIdTranslate.getAux(curPtr));

                    ArrayList<Long> res = new ArrayList<Long>(n);
                    long adjOffset = VertexIdTranslate.getAux(curPtr);
                    adjBuffer.position((int)adjOffset);
                    for(int i=0; i<n; i++) {
                        res.add(VertexIdTranslate.getVertexId(adjBuffer.get()));
                    }
                    callback.receiveOutNeighbors(vertexId, res);
                } else {
                    callback.receiveOutNeighbors(vertexId, new ArrayList<Long>(0));
                }
            }
        } catch (Exception err) {
            throw new RuntimeException(err);
        }
    }


    /**
     * Returns the vertex idx for given vertex in the pointer file, or -1 if not found.
     */
    private long findIdxAndPos(long vertexId, ShardIndex.IndexEntry sparseIndexEntry) {

        assert(sparseIndexEntry.vertex <= vertexId);

        int vertexSeq = sparseIndexEntry.vertexSeq;
        long curvid = sparseIndexEntry.vertex;
        pointerIdxBuffer.position(vertexSeq);
        long ptr = pointerIdxBuffer.get();

        while(curvid < vertexId) {
            try {
                curvid = VertexIdTranslate.getVertexId(ptr);
                if (curvid == vertexId) {
                    return ptr;
                }
                ptr = pointerIdxBuffer.get();
            } catch (BufferUnderflowException bufe) {
                return -1;
            }
        }
        return -1L;
    }

    public synchronized void queryIn(Long queryId, QueryCallback callback) {

        if (queryId < interval.getFirstVertex() || queryId > interval.getLastVertex()) {
            throw new IllegalArgumentException("Vertex " + queryId + " not part of interval:" + interval);
        }

        try {
            /* Step 1: collect adj file offsets for the in-edges */
            final Timer.Context _timer1 = inEdgePhase1Timer.time();
            ArrayList<Integer> offsets = new ArrayList<Integer>();
            int END = (1<<30) - 1;
            int off = inEdgeStartBuffer.get((int) (queryId - interval.getFirstVertex()));

            while(off != END) {
                offsets.add(off);
                adjBuffer.position(off);
                long edge = adjBuffer.get();
                if (VertexIdTranslate.getVertexId(edge) != queryId) {
                    throw new RuntimeException("Mismatch in edge linkage: " + VertexIdTranslate.getVertexId(edge) + " !=" + queryId);
                }
                off = (int) VertexIdTranslate.getAux(edge);
                if (off > END) {
                    throw new RuntimeException("Encoding error: " + edge + " --> " + VertexIdTranslate.getVertexId(edge)
                        + " off : " + VertexIdTranslate.getAux(edge));
                }
            }
            _timer1.stop();

            /* Step 2: collect the vertex ids that contain the offsets by passing over the pointer data */
            /* Find beginning */


            ArrayList<Long> inNeighbors = new ArrayList<Long>(offsets.size());

            Iterator<Integer> offsetIterator = offsets.iterator();
            if (!offsets.isEmpty()) {
                final Timer.Context  _timer2 = inEdgePhase2Timer.time();

                int firstOff = offsets.get(0);
                final Timer.Context  _timer3 = inEdgeIndexLookupTimer.time();
                ShardIndex.IndexEntry startIndex = index.lookupByOffset(firstOff * 8);
                _timer3.stop();

                pointerIdxBuffer.position(startIndex.vertexSeq);

                long last = pointerIdxBuffer.get();
                while (offsetIterator.hasNext()) {
                    off = offsetIterator.next();
                    long ptr = last;

                    while(VertexIdTranslate.getAux(ptr) <= off) {
                        last = ptr;
                        ptr = pointerIdxBuffer.get();
                    }
                    inNeighbors.add(VertexIdTranslate.getVertexId(last));
                }
                _timer2.stop();

            }
            callback.receiveInNeighbors(queryId, inNeighbors);


        } catch (Exception err) {
            throw new RuntimeException(err);
        }
        callback.receiveInNeighbors(queryId, new ArrayList<Long>(0));
    }


    public static String namify(String baseFilename, Long vertexId) throws IOException {
        File f = new File(baseFilename + "_names.dat");
        if (!f.exists()) {
            //	System.out.println("didn't find name file: " + f.getPath());
            return vertexId+"";
        }
        long i = vertexId * 16;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f.getAbsolutePath(), "r");
        raf.seek(i);
        byte[] tmp = new byte[16];
        raf.read(tmp);
        raf.close();
        return new String(tmp) + "(" + vertexId + ")";
    }
}
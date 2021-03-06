/**
 * @author  Aapo Kyrola <akyrola@cs.cmu.edu>
 * @version 1.0
 *
 * @section LICENSE
 *
 * Copyright [2014] [Aapo Kyrola / Carnegie Mellon University]
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
 *
 * Publication to cite:  http://arxiv.org/abs/1403.0701
 */
package edu.cmu.graphchi.preprocessing;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Translates vertices from original id to internal-id and
 * vice versa.  GraphChi translates original ids to "modulo-shifted"
 * ids and thus effectively shuffles the vertex ids. This will lead
 * likely to a balanced edge distribution over the space of vertex-ids,
 * and thus roughly equal amount of edges in each shard. With this
 * trick, we do not need to first count the edge distribution and divide
 * the shard intervals based on that but can skip that step. As a downside,
 * the vertex ids need to be translated back and forth.
 * @author Aapo Kyrola, akyrola@cs.cmu.edu
 */
public class VertexIdTranslate {

    private long vertexIntervalLength;
    private int numShards;

    protected  VertexIdTranslate() {

    }

    public VertexIdTranslate(long vertexIntervalLength, int numShards) {
        this.vertexIntervalLength = vertexIntervalLength;
        this.numShards = numShards;
    }

    /**
     * Translates original vertex id to internal vertex id
     * @param origId
     * @return
     */
    public long forward(long origId) {
        return (origId % numShards) * vertexIntervalLength + origId / numShards;
    }

    /**
     * Translates internal id to original id
     * @param transId
     * @return
     */
    public long backward(long transId) {
        final long shard = transId / vertexIntervalLength;
        final long off = transId % vertexIntervalLength;
        return off * numShards + shard;
    }

    public long getVertexIntervalLength() {
        return vertexIntervalLength;
    }

    public int getNumShards() {
        return numShards;
    }

    public String stringRepresentation() {
        return "vertex_interval_length=" + vertexIntervalLength + "\nnumShards=" + numShards + "\n";
    }

    public static VertexIdTranslate fromString(String s) {
       if ("none".equals(s)) {
           return identity();
       }
       String[] lines = s.split("\n");
       long vertexIntervalLength = -1;
       int numShards = -1;
       for(String ln : lines) {
            if (ln.startsWith("vertex_interval_length=")) {
                vertexIntervalLength = Long.parseLong(ln.split("=")[1]);
            } else if (ln.startsWith("numShards=")) {
                numShards = Integer.parseInt(ln.split("=")[1]);
            }
       }

        if (vertexIntervalLength < 0 || numShards < 0) throw new RuntimeException("Illegal format: " + s);

        return new VertexIdTranslate(vertexIntervalLength, numShards);
    }

    public static VertexIdTranslate fromFile(File f) throws IOException {
        int len = (int) f.length();
        byte[] b = new byte[len];
        FileInputStream fis = new FileInputStream(f);
        fis.read(b);
        fis.close();

        return VertexIdTranslate.fromString(new String(b));
    }

    public static VertexIdTranslate identity() {
        return new VertexIdTranslate() {
            @Override
            public long forward(long origId) {
                return origId;
            }

            @Override
            public long backward(long transId) {
                return transId;
            }

            @Override
            public long getVertexIntervalLength() {
                return -1;
            }

            @Override
            public int getNumShards() {
                return -1;
            }

            @Override
            public String stringRepresentation() {
                return "none";
            }
        };
    }

    /**
     *  Id packing. TODO: move elsewhere.
     */
    private static long ID_MASK   = ((1L<<34)-1) << 30; // 34 bits
    private static long AUX_MASK =  ((1L<<26)-1) << 4; // 26 bits
    private static long TYPE_MASK =  0xf; // 4 bits

    public static final byte DELETED_TYPE = (byte)0xf;

    public static long getVertexId(long vertexPacket) {
        return (vertexPacket & ID_MASK) >> 30;
    }

    public static long getAux(long vertexPacket) {
        return (vertexPacket & AUX_MASK) >> 4;
    }

    public static byte getType(long vertexPacket) {
        return (byte) (vertexPacket & TYPE_MASK);
    }

    public static long encodeVertexPacket(byte edgeType, long vertexId, long aux) {
        assert(aux <  (1L<<26));
        return ((vertexId << 30) & ID_MASK) | ((aux << 4) & AUX_MASK) | (edgeType & TYPE_MASK);
    }

    public static long encodeAsDeleted(long vertexId, long aux) {
        return encodeVertexPacket(DELETED_TYPE, vertexId, aux);
    }

    public static boolean isEdgeDeleted(long edgePacket) {
        return getType(edgePacket) == DELETED_TYPE;
    }

}

package com.twitter.pers.multicomp;

import edu.cmu.graphchi.util.HugeFloatMatrix;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * @author Aapo Kyrola, akyrola@cs.cmu.edu, akyrola@twitter.com
 */
public class WeightUtil {

    public static void loadWeights(ComputationInfo compInfo, HugeFloatMatrix weightMatrix, float cutOff, boolean weightedAlgorithm) throws IOException {
        int compId = compInfo.getId();
        BufferedReader rd = new BufferedReader(new FileReader(compInfo.getInputFile()));
        String ln;
        while((ln = rd.readLine()) != null) {
            if (ln.contains("\t")) {
                String[] toks = ln.split("\t");
                int vertexId = Integer.parseInt(toks[0]);
                float weight = Float.parseFloat(toks[toks.length - 1]);
                if (vertexId < weightMatrix.getNumRows())
                    weightMatrix.setValue(vertexId, compId, weight + weightMatrix.getValue(vertexId, compId));
            }
        }
        rd.close();

        if (weightedAlgorithm) {
            weightMatrix.zeroLessThan(cutOff);
        } else {
            weightMatrix.binaryFilter(cutOff, 1.0f);
        }
    }

}
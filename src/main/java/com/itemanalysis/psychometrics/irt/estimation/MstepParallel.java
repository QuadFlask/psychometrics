/**
 * Copyright 2014 J. Patrick Meyer
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.itemanalysis.psychometrics.irt.estimation;

import com.itemanalysis.psychometrics.distribution.DistributionApproximation;
import com.itemanalysis.psychometrics.irt.model.IrmType;
import com.itemanalysis.psychometrics.irt.model.ItemResponseModel;
import com.itemanalysis.psychometrics.uncmin.DefaultUncminOptimizer;
import com.itemanalysis.psychometrics.uncmin.UncminException;

import java.util.Arrays;
import java.util.concurrent.RecursiveAction;

/**
 * Mstep of the EM algorithm for estimating item parameters in Item Response Theory.
 * Computations can be done in parallel but performance gains appear to be minimal.
 */
public class MstepParallel extends RecursiveAction {

    private ItemResponseModel[] irm = null;
    private DistributionApproximation latentDistribution = null;
    private EstepEstimates estepEstimates = null;
    private int start = 0;
    private int length = 0;
    private static int PARALLEL_THRESHOLD = 100;
    private DefaultUncminOptimizer optimizer = null;
    private int[] codeCount = new int[4];

//    private QNMinimizer qn = null;

    public MstepParallel(ItemResponseModel[] irm, DistributionApproximation latentDistribution, EstepEstimates estepEstimates, int start, int length){
        this.irm = irm;
        this.latentDistribution = latentDistribution;
        this.estepEstimates = estepEstimates;
        this.start = start;
        this.length = length;
        optimizer = new DefaultUncminOptimizer(10);
//        qn = new QNMinimizer(15, true);
//        qn.setRobustOptions();
//        qn.shutUp();
    }

    /**
     * Mstep computation when the number of items is less than the threshold or when the
     * stopping condition has been reached. For each item, it uses the optimizer to obtain
     * the estimates that maximize the marginal likelihood.
     */
    protected void computeDirectly(){
        ItemLogLikelihood itemLogLikelihood = new ItemLogLikelihood();
        double[] initialValue = null;
        int nPar = 1;
        double[] param = null;

        for(int j=start;j<start+length;j++){
            nPar = irm[j].getNumberOfParameters();

            itemLogLikelihood.setModel(irm[j], latentDistribution, estepEstimates.getRjkAt(j), estepEstimates.getNt());
            initialValue = irm[j].getItemParameterArray();

            try{
                optimizer.minimize(itemLogLikelihood, initialValue, true, false, 500);
                param = optimizer.getParameters();

                if(optimizer.getTerminationCode()>3) codeCount[0]++;

            }catch(UncminException ex){
                codeCount[0]++;
                ex.printStackTrace();
            }

//            param = qn.minimize(itemLogLikelihood,1e-8,initialValue,500);

            if(irm[j].getType()==IrmType.L3 || irm[j].getType()==IrmType.L4){
                if(nPar==4){
                    if(param[0]<0) codeCount[1]++;
                    if(param[2]<0) codeCount[2]++;
                    if(param[3]>1) codeCount[3]++;
                    irm[j].setProposalDiscrimination(param[0]);
                    irm[j].setProposalDifficulty(param[1]);
                    irm[j].setProposalGuessing(Math.min(1.000, Math.max(param[2], 0.001)));//set negative estimates to just above zero
                    irm[j].setProposalSlipping(Math.max(0.60, Math.min(param[3], 0.999)));//set negative estimates to just below 1.
                }else if(nPar==3){
                    if(param[0]<0) codeCount[1]++;
                    if(param[2]<0) codeCount[2]++;
                    irm[j].setProposalDiscrimination(param[0]);
                    irm[j].setProposalDifficulty(param[1]);
                    irm[j].setProposalGuessing(param[2]);
                    irm[j].setProposalGuessing(Math.min(1.000, Math.max(param[2], 0.001)));//set negative estimates to just above zero
                }else if(nPar==2){
                    if(param[0]<0) codeCount[1]++;
                    irm[j].setProposalDiscrimination(param[0]);
                    irm[j].setProposalDifficulty(param[1]);
                }else{
                    irm[j].setProposalDifficulty(param[0]);
                }

            }else if(irm[j].getType()==IrmType.GPCM){
                irm[j].setProposalDiscrimination(param[0]);
                irm[j].setProposalStepParameters(Arrays.copyOfRange(param, 1, param.length));

            }else if(irm[j].getType()==IrmType.PCM2){
                irm[j].setProposalStepParameters(param);
            }

        }
    }

    /**
     * Parallel processing handled here.
     */
    @Override
    protected void compute(){
        if(length<=PARALLEL_THRESHOLD){
            computeDirectly();
            return;
        }else{
            int split = length/2;

            MstepParallel mstep1 = new MstepParallel(irm, latentDistribution, estepEstimates, start, split);
            MstepParallel mstep2 = new MstepParallel(irm, latentDistribution, estepEstimates, start+split, length-split);
            invokeAll(mstep1, mstep2);
        }
    }

    //TODO more testing is needed for the method below.
    public DistributionApproximation updateLatentDistribution(){
        double sumNk = estepEstimates.getSumNt();
        double[] nk = estepEstimates.getNt();

        //Update posterior probabilities and compute new mean and standard deviation.
        for(int k=0;k<nk.length;k++){
            latentDistribution.setDensityAt(k, nk[k]/sumNk);
        }

        double newMean = latentDistribution.getMean();
        double newSD = latentDistribution.getStandardDeviation();

        //Compute linear transformation that set distribution mean to 0 and standard deviation to 1
        double slope = 1.0/newSD;
        double intercept = -slope*newMean;
        double point = 0;
        for(int k=0;k<nk.length;k++){
            point = latentDistribution.getPointAt(k);
            latentDistribution.setPointAt(k, point*slope+intercept);
        }

        //Transform item parameters
        for(int j=0;j<irm.length;j++){
            irm[j].scale(intercept, slope);
        }

        return latentDistribution;
    }

    public int[] getCodeCount(){
        return codeCount;
    }


}

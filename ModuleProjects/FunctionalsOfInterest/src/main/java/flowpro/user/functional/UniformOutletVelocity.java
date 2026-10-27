/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.user.functional;

import flowpro.api.ElementData;
import flowpro.api.FlowProProperties;
import flowpro.api.Functional;
import java.io.IOException;

/**
 *
 * @author obublik
 */
public class UniformOutletVelocity implements Functional {

    double[] uOut;
    double c;
    
    @Override
    public int getN(){
        return 1;
    }
    
    @Override
    public void init(FlowProProperties props) throws IOException {
         this.uOut = props.getDoubleArray("uOut");
         this.c = props.getDouble("c");
    }

    @Override
    public double[] insideValue(double[] W, double[] dW, ElementData elemData) {
        return new double[]{0};
    }

    @Override
    public double[] boundaryValue(double[] W, double[] dW, double[] n, int TT, ElementData elemData) {
        if (TT ==-2 || TT == -3) {
            //double out = 0;
            //int dim = 2;
            //for(int d = 0; d < dim; d++){
            //    out += c*(W[d+1]/W[0] - uOut[d])*(W[d+1]/W[0] - uOut[d])/2;
            //}
            //return out;
            return new double[]{1};
        } else {
            return new double[]{0};
        }
    }
    
    @Override
    public double combineFunctionals(double[] functionals){
        return functionals[0];
    }
}

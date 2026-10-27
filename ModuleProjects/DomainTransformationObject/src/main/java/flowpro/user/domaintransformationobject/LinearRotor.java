/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.user.domaintransformationobject;

import flowpro.api.Complex;
import static flowpro.api.Complex.copy;
import flowpro.api.DomainTransformationObject;
import flowpro.api.FlowProProperties;
import java.io.IOException;

/**
 *
 * @author obublik
 */
public class LinearRotor implements DomainTransformationObject{
    
    private double e;
    private double rMin;
    private double rMax;
    
    @Override
    public void init(FlowProProperties props) throws IOException {
        e = props.getDouble("e");
        rMin = props.getDouble("rMin");
        rMax = props.getDouble("rMax");
    }
    
    @Override
    public double[] transform(double[] X) {
        if(X[1] < rMin){
            return new double[]{X[0], X[1], X[2]};
        } else {
            double c = (X[1]-rMin)/(rMax-rMin);
            return new double[]{X[0], X[1] + c*e*Math.cos(X[2]), X[2]};
        }
    }
    
    @Override
    public Complex[] transformComplex(Complex[] X){
        Complex[] Y = new Complex[X.length];
        Y[0] = copy(X[0]);
        Y[1] = copy(X[1]);
        Y[2] = copy(X[2]);
        
        return Y;
    }
}
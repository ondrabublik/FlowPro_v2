/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.user.domaintransformationobject;

import flowpro.api.Complex;
import static flowpro.api.Complex.copy;
import static flowpro.api.Complex.cos;
import static flowpro.api.Complex.multiply;
import static flowpro.api.Complex.sin;
import flowpro.api.DomainTransformationObject;
import flowpro.api.FlowProProperties;

/**
 *
 * @author obublik
 */
public class CylindricalTransform2PI implements DomainTransformationObject{
    
    @Override
    public void init(FlowProProperties props){
        
    }
    
    @Override
    public double[] transform(double[] X) {
//        return new double[]{X[0], X[1] * Math.cos(2*Math.PI*X[2]), -X[1] * Math.sin(2*Math.PI*X[2])};
        return new double[]{X[0], X[1] * Math.cos(X[2]), X[1] * Math.sin(X[2])};
    }
    
    @Override
    public Complex[] transformComplex(Complex[] X){
        Complex[] Y = new Complex[X.length];
        Complex c = new Complex(2*Math.PI,0);
        Y[0] = copy(X[0]);
        Y[1] = multiply(X[1],cos(multiply(c,X[2])));
        Y[2] = multiply(multiply(X[1],sin(multiply(c,X[2]))),new Complex(-1,0));
                
        return Y;
    }
}
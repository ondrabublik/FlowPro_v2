/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.user.domaintransformationobject;

import flowpro.api.Complex;
import static flowpro.api.Complex.*;
import flowpro.api.DomainTransformationObject;
import flowpro.api.FlowProProperties;

/**
 *
 * @author obublik
 */
public class NURBS2D implements DomainTransformationObject {
    
    public void init(FlowProProperties props){
        
    }
    
    public double[] transform(double[] X){
//        double tx = X[0];
//        double ty = X[1];
//        
//        double x = tx;
//        double y = ty + ty*(1-ty);
//        return new double[]{x,y};
        //return X;

        Complex[] Xc = new Complex[X.length];
        for (int i = 0; i < X.length; i++) {
            Xc[i] = new Complex(X[i], 0);
        }
        Complex[] Yc = transformComplex(Xc);
        return new double[]{Yc[0].getRe(),Yc[1].getRe()};
    }
    
    public Complex[] transformComplex(Complex[] X){
        Complex[] Y = new Complex[X.length];
        Y[0] = X[0];
        Y[1] = subtract(X[1],multiply(X[1],subtract(new Complex(1,0),X[1])));
        //Y[1] = X[1];
        return Y;
    }
}

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.user.equation;

import flowpro.api.ElementData;

/**
 *
 * @author obublik
 */
public class NavierStokesBody extends NavierStokes{
    
    @Override
    public boolean isSourcePresent() {
        return true;
    }

    @Override
    public double[] sourceTerm(double[] W, double[] dW, ElementData elem) { // zdrojovy clen
        double[] source = new double[nEqs];
        double g = 0.01;
        //if(elem.currentT > 5){
        //    g = -g;
        //}
        source[1] = W[0]*g;
        return source;
    }
    
}

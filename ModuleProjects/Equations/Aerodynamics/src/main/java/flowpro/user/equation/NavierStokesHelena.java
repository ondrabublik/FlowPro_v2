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
public class NavierStokesHelena extends NavierStokes {
    
    @Override
    public boolean isSourcePresent() {
        return true;
    }
    
    @Override
    public double[] sourceTerm(double[] W, double[] dW, ElementData elem) { // zdrojovy clen
        double[] p = new double[nEqs];
        double[] uAverages = elem.integralMonitor;
        int i = (int)((uAverages.length-1)*elem.currentX[1]);
        p[1] = 10*((0.5-Math.abs(elem.currentX[1]-0.5))-3*uAverages[i]);
        return p;
    }   
}

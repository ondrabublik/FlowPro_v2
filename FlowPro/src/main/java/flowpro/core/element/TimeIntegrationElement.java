/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.core.element;

import flowpro.api.FlowProProperties;

/**
 *
 * @author obublik
 */
public abstract class TimeIntegrationElement {

    boolean isImplicit;
    Element elem;
    int nEqs;
    int nBasis;
    int nFaces;

    /**
     * spatial residuum R(W) evaluated at the beginning of the time step (in the
     * first Newton iteration or in the first stage), used for the convergence
     * monitoring
     */
    public double[] spatialResiduum;

    void set(Element elem) {
        this.elem = elem;
        nEqs = elem.nEqs;
        nBasis = elem.nBasis;
        nFaces = elem.nFaces;
        spatialResiduum = new double[nEqs * nBasis];
    }

    abstract public void init(FlowProProperties props);

    abstract public int getOrder();

    abstract public boolean isImplicit();
    
    abstract public String getLocalSolverType();
}

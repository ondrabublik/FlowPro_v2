package flowpro.core.meshDeformation;

import flowpro.api.Equation;
import flowpro.api.FluidForces;
import flowpro.api.Mat;
import flowpro.api.MeshMove;
import flowpro.core.Integration.Face;
import flowpro.core.element.Element;
import flowpro.core.Parameters;
import java.io.*;

/**
 *
 * @author obublik
 */
public class Deformation2DRigid extends Deformation {

    private final FluidForces[] fluidForces;

    public Deformation2DRigid(Parameters par, Equation eqn, int[][] TEale) throws IOException {
        super(par, eqn, TEale);

        fluidForces = new FluidForces[nBodies];
    }

    @Override
    public void newMeshPositionAndVelocity(Element[] elems, int timeOrder, double dt, double dto, MeshMove[] mshMov) {

        double a1 = 1.0 / dt;
        double a2 = -1.0 / dt;
        double a3 = 0;

        if (timeOrder > 1 && dto > 0) { // dto <= 0: first step, BDF1
            a1 = (2 * dt + dto) / (dt * (dt + dto));  // 3/(2*dt); 
            a2 = -(dt + dto) / (dt * dto);  // -2/dt;
            a3 = dt / (dto * (dt + dto));  // 1/(2*dt);
        }

        // multiple blending function
        for (Element elem : elems) {
            for (int j = 0; j < elem.nVertices; j++) {
                for (int d = 0; d < elem.dim; d++) {
                    elem.vertices[j][d] = 0;
                }
            }
            for (int k = 0; k < nBodies; k++) {
                double[] moveTranslation = mshMov[k].getTranslation();
                double[] moveRotation = mshMov[k].getRotation();
                for (int j = 0; j < elem.nVertices; j++) {
                    double xNew = (Math.cos(moveRotation[0]) * (elem.vertices0[j][0] - center[0][k]) - Math.sin(moveRotation[0]) * (elem.vertices0[j][1] - center[1][k]) + center[0][k] - elem.vertices0[j][0]) * nBodies + elem.vertices0[j][0] + moveTranslation[0] * nBodies;
                    double yNew = (Math.sin(moveRotation[0]) * (elem.vertices0[j][0] - center[0][k]) + Math.cos(moveRotation[0]) * (elem.vertices0[j][1] - center[1][k]) + center[1][k] - elem.vertices0[j][1]) * nBodies + elem.vertices0[j][1] + moveTranslation[1] * nBodies;
                    elem.vertices[j][0] += ((1 - elem.blendFun[j][k]) * elem.vertices0[j][0] + elem.blendFun[j][k] * xNew) / nBodies;
                    elem.vertices[j][1] += ((1 - elem.blendFun[j][k]) * elem.vertices0[j][1] + elem.blendFun[j][k] * yNew) / nBodies;
                }
            }
        }

        for (Element elem : elems) {
            for (int j = 0; j < elem.nVertices; j++) {
                for (int d = 0; d < elem.dim; d++) {
                    elem.U[j][d] = a1 * elem.vertices[j][d] + a2 * elem.verticesOld[j][d] + a3 * elem.verticesOld2[j][d];
                }
            }
        }
    }

    @Override
    public void nextTimeLevel(Element[] elems) {
        for (Element elem : elems) {
            for (int j = 0; j < elem.nVertices; j++) {
                for (int d = 0; d < elem.dim; d++) {
                    elem.verticesOld2[j][d] = elem.verticesOld[j][d];
                    elem.verticesOld[j][d] = elem.vertices[j][d];
                }
            }
        }
    }


    @Override
    public void calculateForces(Element[] elems, MeshMove[] mshMov) {

        for (int b = 0; b < nBodies; b++) {
            double[] moveTranslation = mshMov[b].getTranslation();
            double[] totalTranslationForce = new double[2];
            double[] totalRotationForce = new double[1];
            for (Element elem : elems) {
                for (int r = 0; r < elem.nFaces; r++) {
                    if (elem.TEale[r] == b + 2 && elem.insideMetisDomain) {
                        Face face = elem.Int.faces[r];

                        double[] force = face.integrateLeft((double[] w, double[] dw, double[] n)
                                -> eqn.stressVector(w, dw, n), elem.W, elem.n[r]);
                        double fx = -force[0];
                        double fy = -force[1];

                        totalTranslationForce[0] += fx;
                        totalTranslationForce[1] += fy;
                        totalRotationForce[0] += -fx * (elem.Xes[r][1] - (center[1][b] + moveTranslation[1])) + fy * (elem.Xes[r][0] - (center[0][b] + moveTranslation[0]));
                    }
                }
            }
            fluidForces[b] = new FluidForces(totalTranslationForce, totalRotationForce);
        }
    }

    
    @Override
    public FluidForces[] getFluidForces() {
        return fluidForces;
    }
}

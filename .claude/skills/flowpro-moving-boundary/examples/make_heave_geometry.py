"""cylinder D = 1 in a circular domain R = 10 (outer -3, ALE 1; cylinder -1, ALE 2); usage: python make_heave_geometry.py simulations/NAME/mesh"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "flowpro-mesh", "scripts"))  # geo2d.py
from geo2d import Geometry, circle
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
g = Geometry(mesh_size=1.0, min_angle=28, grading=0.2)
g.loop(circle(0, 0, 10, n=80, type=-3, ale=1))
g.loop(circle(0, 0, 0.5, n=128, type=-1, h=0.025, ale=2))
g.region_circle(0, 0, 0.75, 0.03)
g.save(os.path.join(out, "geometry.geo2d"))

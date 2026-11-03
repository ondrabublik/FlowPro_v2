"""cylinder D = 1 at (0, 0) in a channel [-8, 22] x [-8, 8]; usage: python example_cylinder.py simulations/NAME/mesh"""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # geo2d.py
from geo2d import Geometry, rectangle, circle
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
g = Geometry(mesh_size=1.0, min_angle=28, grading=0.25)
# bottom, right, top, left
g.loop(rectangle(-8, -8, 22, 8, types=(-4, -3, -4, -2), ale=1))
g.loop(circle(0, 0, 0.5, n=96, type=-1, h=0.035, ale=2))
g.region_box(-1, -1.2, 12, 1.2, 0.15)
g.region_circle(0, 0, 1.0, 0.06)
g.save(os.path.join(out, "geometry.geo2d"))

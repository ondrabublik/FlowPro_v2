#!/bin/sh
# Runs the simulation given in args.txt (or use: ./run.sh -g GEOMETRY -s SIMULATION -p KEY=VALUE)
cd "$(dirname "$0")"
exec java -Xmx16g -Xss10m -jar FlowPro.jar run "$@"

#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/host-checks/classes
java com.sun.tools.javac.Main -d build/host-checks/classes \
    app/src/main/java/com/example/cinemacamera/FrameGeometry.java \
    app/src/main/java/com/example/cinemacamera/GrainTile.java \
    app/src/main/java/com/example/cinemacamera/LinearPhoto.java \
    app/src/main/java/com/example/cinemacamera/RawDeveloper.java \
    tools/HostChecks.java tools/RawChecks.java tools/ParseJava.java
java -cp build/host-checks/classes HostChecks build/host-checks/grain.rgba
java -cp build/host-checks/classes RawChecks
java -cp build/host-checks/classes ParseJava app/src/main/java
export MESA_SHADER_CACHE_DISABLE=true
python tools/check-shader.py build/host-checks/grain.rgba

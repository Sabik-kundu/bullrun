#!/bin/sh
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/bullrun/*.java && java -cp out bullrun.Main

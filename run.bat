@echo off
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\bullrun\*.java && java -cp out bullrun.Main

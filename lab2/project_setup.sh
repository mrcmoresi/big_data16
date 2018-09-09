#!/bin/bash

PROJECT=$1

mkdir -p $PROJECT/src/{main,test}/{java,resources,scala}
mkdir $PROJECT/{lib,project,target,doc}

VERSION=$(scala -version 2>&1 | sed 's/^.*version \([0-9.]*\).*/\1/')

echo 'name := "'$PROJECT'"' > $PROJECT/$PROJECT.sbt
echo 'version := "1.0"' >> $PROJECT/$PROJECT.sbt
echo 'scalaVersion := "'$VERSION'"' >> $PROJECT/$PROJECT.sbt
echo 'libraryDependencies += "org.apache.spark" %% "spark-core" % "2.0.0" % "provided"' >> $PROJECT/$PROJECT.sbt

echo 'bin/
target/' >> $PROJECT/.gitignore

#change to project dir
cd $PROJECT
#create eclipse files.
sbt eclipse

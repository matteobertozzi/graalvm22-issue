#!/bin/bash

mvn clean package -DskipTests
cp target/demo-1.0-SNAPSHOT.jar demo.jar
mvn clean test -DargLine="-Dtruffle.class.path.append=demo.jar" -Dtest=TestDemoInstrument

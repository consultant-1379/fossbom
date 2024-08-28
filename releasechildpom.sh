#!/bin/bash

#list the contents of the dir. this will make it easier to troubleshoot issues with the build.
ls

mvn -V -f Network-Health-Monitor-Bom.xml clean deploy
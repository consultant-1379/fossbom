#!/bin/bash

#list the contents of the dir. this will make it easier to troubleshoot issues with the build.
ls

for f in $(pwd)/*-Bom.xml; do 
    echo '***********' ${f} '*************'
	mvn -V -f ${f} clean install
done


#!/bin/bash
sudo apt install openjdk-11-jdk maven

cd ~
wget https://archive.apache.org/dist/solr/solr/9.0.0/solr-9.0.0.tgz
wget https://cdn.azul.com/blogs/datasets/solr/wiki.json.gz
gzip -d wiki.json.gz
git clone https://github.com/uschindler/solr-benchmark.git

tar xvzf solr-9.0.0.tgz
cd solr-9.0.0/bin/
./solr start
./solr delete -c test
./solr create_core -c test

cd ~/solr-benchmark/
mvn clean package
java -cp target/solr-benchmark-0.0.2-SNAPSHOT.jar Upload ../wiki.json

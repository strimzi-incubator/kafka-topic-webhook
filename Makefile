PROJECT_NAME=kafka-topic-initializer

all: java_build docker_build docker_push
clean: java_clean

gen_certs:
	echo "Generating certificates ..."
	cfssl genkey -initca tls/ca.json | cfssljson -bare tls/ca
	cfssl gencert -ca tls/ca.pem -ca-key tls/ca-key.pem tls/initializer.json | cfssljson -bare tls/initializer
	mv tls/initializer.pem src/main/resources/initializer.pem
	mv tls/initializer-key.pem src/main/resources/initializer-key.pem

include ./Makefile.docker

include ./Makefile.java

.PHONY: build clean

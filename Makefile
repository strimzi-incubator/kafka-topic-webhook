PROJECT_NAME=kafka-topic-webhook

all: java_build docker_build docker_push
clean: java_clean

gen_certs:
	echo "Generating certificates ..."
	cfssl genkey -initca tls/ca.json | cfssljson -bare tls/ca
	cfssl gencert -ca tls/ca.pem -ca-key tls/ca-key.pem tls/webhook.json | cfssljson -bare tls/webhook
	mv tls/webhook.pem src/main/resources/webhook.pem
	mv tls/webhook-key.pem src/main/resources/webhook-key.pem
	sed -i '' "s/.*caBundle.*/    caBundle: $$(cat tls/ca.pem | base64 | tr -d '\n')/" openshift.yaml

include ./Makefile.docker

include ./Makefile.java

.PHONY: build clean

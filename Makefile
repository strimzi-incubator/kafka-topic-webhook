PROJECT_NAME=kafka-topic-initializer

all: java_build docker_build docker_push
clean: java_clean

include ./Makefile.docker

include ./Makefile.java

.PHONY: build clean

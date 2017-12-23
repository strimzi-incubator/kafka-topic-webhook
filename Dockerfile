FROM enmasseproject/java-base:8-3

ARG version=latest
ENV VERSION ${version}
ADD target/kafka-topic-initializer.jar /

CMD ["/bin/launch_java.sh", "/kafka-topic-initializer.jar"]
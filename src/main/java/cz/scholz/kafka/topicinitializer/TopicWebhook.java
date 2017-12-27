package cz.scholz.kafka.topicinitializer;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.kafka.admin.AdminUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopicWebhook extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(TopicWebhook.class.getName());

    private static final int port = 8443;
    private static String zookeeper;

    public TopicWebhook(TopicWebhookConfig config) throws Exception {
        log.info("Creating Kafka Topic Initializer (KTI) controller");

        zookeeper = config.getZookeeper();
        log.info("Using Zookeeper {}", zookeeper);
    }

    /*
    Start the verticle
     */
    @Override
    public void start(Future<Void> start) {
        log.info("Starting KTI controller");
        startHttpServer(res -> {
            if (res.succeeded()) {
                log.info("KTI controller created");
                start.complete();
            }
            else {
                log.info("KTI controller failed to start", res.cause());
                start.fail(res.cause());
            }
        });
    }

    /*
    Create and start HTTP server
     */
    private void startHttpServer(Handler<AsyncResult<Void>> resultHandler) {
        Router router = configureRouter();

        HttpServerOptions httpOptions = new HttpServerOptions();
        setSsl(httpOptions);

        log.info("Starting web server on port {}", port);
        vertx.createHttpServer(httpOptions)
                .requestHandler(router::accept)
                .listen(port, res -> {
                    if (res.succeeded()) {
                        log.info("Web server started");
                        resultHandler.handle(Future.succeededFuture());
                    }
                    else {
                        log.error("Web server failed to start", res.cause());
                        resultHandler.handle(Future.failedFuture(res.cause()));
                    }
                });
    }

    /*
    Configure SSL for HTTP server with key from resources
    TODO: Pass the key as ConfigMap / Env. variable
     */
    private void setSsl(HttpServerOptions httpServerOptions) {
        httpServerOptions.setSsl(true);

        PemKeyCertOptions pemKeyCertOptions = new PemKeyCertOptions()
                .setKeyValue(Buffer.buffer(new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/initializer-key.pem"))).lines().collect(Collectors.joining("\n"))))
                .setCertValue(Buffer.buffer(new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/initializer.pem"))).lines().collect(Collectors.joining("\n"))));
        httpServerOptions.setPemKeyCertOptions(pemKeyCertOptions);
    }

    /*
    Setup Vert.x router (just a single route)
     */
    private Router configureRouter() {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route("/*").handler(this::handleRequest);

        return router;
    }

    /*
    Triggered by incomming requests
     */
    private void handleRequest(RoutingContext routingContext) {
        log.info("Received {} request on {} with body {}", routingContext.request().method().name(), routingContext.request().absoluteURI(), routingContext.getBodyAsString());

        JsonObject reviewReq = routingContext.getBodyAsJson();
        if ("AdmissionReview".equals(reviewReq.getString("kind"))) {
            JsonObject pod = reviewReq.getJsonObject("spec").getJsonObject("object");
            admit(pod, res -> {
                JsonObject result = res.result();
                log.info("Responding with body {}", result.toString());
                routingContext.response().setStatusCode(HttpResponseStatus.OK.code()).putHeader("content-type", "application/json; charset=utf-8").end(result.encodePrettily());
            });
        }
        else {
            log.error("Kind is not AdmissionReview but {}", reviewReq.getString("kind"));
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).setStatusMessage("Received unexpected request!").end();

        }
    }

    /*
    Decide whether the Pod should be admitted or not
     */
    private void admit(JsonObject pod, Handler<AsyncResult<JsonObject>> handler) {
        log.info("Admitting pod {} ({})", pod.getString("generateName"), pod);

        JsonObject annotations = pod.getJsonObject("annotations", new JsonObject());

        if (annotations.containsKey("topic-initializer.kafka.scholz.cz/topics")) {
            List<Future> topicFutures = new ArrayList<>();

            String topicAnnotation = annotations.getString("topic-initializer.kafka.scholz.cz/topics");
            JsonArray topics = new JsonArray(topicAnnotation);

            for (Object topic : topics.getList()) {
                String topicName = (String)topic;
                Future completion = Future.future();
                topicFutures.add(completion);
                log.info("Pod {} requires topic {}", pod.getString("generateName"), topicName);
                handleTopic(topicName, completion.completer());
            }

            CompositeFuture.all(topicFutures).setHandler(res -> {
               if (res.succeeded()) {
                   log.info("All topic subfutures completed successfully");
                   handler.handle(Future.succeededFuture(createAdmissionReviewResult(true, null)));
               }
               else {
                   String statusMessage = "Rejected by Kafka Topic Initializer. See logs for more details.";
                   log.error("Some topic subfutures failed. Rejecting admission with error message '{}'.", statusMessage);
                   handler.handle(Future.succeededFuture(createAdmissionReviewResult(false, statusMessage)));
               }
            });

        }
        else {
            log.info("Pod {} doesn't contain any relevant annotation and will be allowed", pod.getString("generateName"));
            handler.handle(Future.succeededFuture(createAdmissionReviewResult(true)));
        }
    }

    /*
    Handles the individual topic
     */
    private void handleTopic(String topic, Handler<AsyncResult<Void>> handler) {
        AdminUtils admin = AdminUtils.create(vertx, zookeeper);

        admin.topicExists(topic, res -> {
            if (res.succeeded()) {
                if (res.result() == true) {
                    log.info("Topic {} already exists", topic);
                    handler.handle(Future.succeededFuture());
                }
                else {
                    log.info("Topic {} doesn't exists", topic);

                    admin.createTopic(topic, 1, 1, res2 -> {
                        if (res2.succeeded()) {
                            log.info("Topic {} created", topic);
                            handler.handle(Future.succeededFuture());
                        }
                        else {
                            log.error("Failed to create topic " + topic, res2.cause());
                            handler.handle(Future.failedFuture("Failed to create topic " + topic + ". "));
                        }
                    });
                }
            }
            else {
                log.error("Failed to query topic " + topic, res.cause());
                handler.handle(Future.failedFuture("Failed to query topic " + topic + ". "));
            }
        });
    }

    /*
    Generate review status (with message)
     */
    private JsonObject createReviewStatus(Boolean allowed, String statusMessage) {
        if (statusMessage != null) {
            JsonObject status = new JsonObject()
                    .put("status", "Failure")
                    .put("message", statusMessage)
                    .put("reason", statusMessage);
            return new JsonObject().put("allowed", allowed).put("status", status);
        }
        else {
            return new JsonObject().put("allowed", allowed);
        }
    }

    /*
    Generate ReviewResult based on status passed as parameter (without message)
     */
    private JsonObject createAdmissionReviewResult(Boolean allowed) {
        return createAdmissionReviewResult(allowed, null);
    }

    /*
    Generate ReviewResult based on status passed as parameter
     */
    private JsonObject createAdmissionReviewResult(Boolean allowed, String status) {
        JsonObject result = new JsonObject();
        result.put("kind", "AdmissionReview");
        result.put("apiVersion", "admission.k8s.io/v1alpha1");
        result.put("status", createReviewStatus(allowed, status));

        return result;
    }
}

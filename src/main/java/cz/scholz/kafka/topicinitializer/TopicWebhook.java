package cz.scholz.kafka.topicinitializer;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopicWebhook extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(TopicWebhook.class.getName());

    private static final int port = 8443;

    public TopicWebhook(TopicWebhookConfig config) throws Exception {
        log.info("Creating Kafka Topic Initializer (KTI) controller");
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
            JsonObject responseBody = createAdmissionReviewResult(admit(pod));
            log.info("Responding with body {}", responseBody.toString());
            routingContext.response().setStatusCode(HttpResponseStatus.OK.code()).putHeader("content-type", "application/json; charset=utf-8").end(responseBody.toString());
        }
        else {
            log.error("Kind is not AdmissionReview but {}", reviewReq.getString("kind"));
            routingContext.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).setStatusMessage("Received unexpected request!").end();

        }
    }

    /*
    Decide whether the Pod should be admitted or not
     */
    private JsonObject admit(JsonObject pod) {
        log.info("Admitting pod {} ({})", pod.getString("generateName"), pod);

        JsonObject annotations = pod.getJsonObject("annotations", new JsonObject());

        if (annotations.containsKey("topic-initializer.kafka.scholz.cz/topics")) {
            Boolean allowed = true;
            String status = null;

            String topicAnnotation = annotations.getString("topic-initializer.kafka.scholz.cz/topics");
            JsonArray topics = new JsonArray(topicAnnotation);

            for (Object topic : topics.getList()) {
                String topicName = (String)topic;
                log.info("Pod {} requires topic {}", pod.getString("generateName"), topicName);
            }

            return createReviewStatus(allowed, status);
        }
        else {
            log.info("Pod {} doesn't contain any relevant annotation and will be allowed", pod.getString("generateName"));
            return createReviewStatus(true);
        }
    }

    /*
    Generate review status (without message)
     */
    private JsonObject createReviewStatus(Boolean allowed) {
        return createReviewStatus(allowed, null);
    }

    /*
    Generate review status (with message)
     */
    private JsonObject createReviewStatus(Boolean allowed, String status) {
        if (status != null) {
            return new JsonObject().put("allowed", allowed).put("status", status);
        }
        else {
            return new JsonObject().put("allowed", allowed);
        }
    }

    /*
    Generate ReviewResult based on status passed as parameter
     */
    private JsonObject createAdmissionReviewResult(JsonObject status) {
        JsonObject result = new JsonObject();
        result.put("kind", "AdmissionReview");
        result.put("apiVersion", "admission.k8s.io/v1alpha1");
        result.put("status", status);

        return result;
    }
}

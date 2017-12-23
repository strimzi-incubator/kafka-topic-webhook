package cz.scholz.kafka.topicinitializer;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
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

    private void setSsl(HttpServerOptions httpServerOptions) {
        httpServerOptions.setSsl(true);

        PemKeyCertOptions pemKeyCertOptions = new PemKeyCertOptions()
                .setKeyValue(Buffer.buffer(new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/initializer-key.pem"))).lines().collect(Collectors.joining("\n"))))
                .setCertValue(Buffer.buffer(new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/initializer.pem"))).lines().collect(Collectors.joining("\n"))));
        httpServerOptions.setPemKeyCertOptions(pemKeyCertOptions);
    }

    private Router configureRouter() {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route("/*").handler(this::handleRequest);

        return router;
    }

    private void handleRequest(RoutingContext routingContext) {
        log.info("Received {} request on {} with body {}", routingContext.request().method().name(), routingContext.request().absoluteURI(), routingContext.getBodyAsString());

        //new JSON().deserialize(routingContext.getBodyAsString(), V1alpha1Admission)

        routingContext.response().setStatusCode(HttpResponseStatus.NOT_IMPLEMENTED.code()).end();
    }

    private void admit() {
        //log.info("Received {} request on {} with body {}", routingContext.request().method().name(), routingContext.request().absoluteURI(), routingContext.getBodyAsString());


        //routingContext.response().setStatusCode(HttpResponseStatus.NOT_IMPLEMENTED.code()).end();
    }
}

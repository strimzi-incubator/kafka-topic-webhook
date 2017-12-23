package cz.scholz.kafka.topicinitializer;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopicWebhook extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(TopicWebhook.class.getName());

    private static final int port = 8080;

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

    private Router configureRouter() {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route("/*").handler(this::admit);

        return router;
    }

    private void admit(RoutingContext routingContext) {
        log.info("Received {} request on {} with body {}", routingContext.request().method().name(), routingContext.request().absoluteURI(), routingContext.getBodyAsString());
        routingContext.response().setStatusCode(HttpResponseStatus.NOT_IMPLEMENTED.code()).end();
    }
}

package com.juzo.ai.ordermanager.risk.grpc;

import com.juzo.ai.ordermanager.risk.service.BuyingPowerCache;
import com.juzo.ai.ordermanager.risk.service.PriceCache;
import com.juzo.ai.ordermanager.risk.service.RiskEvaluationService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ClientInterceptors;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * The same interceptors the services use (client in Order, server in Risk) must carry one trace across the call:
 * the server span has the client span as parent and the same trace id.
 */
@ExtendWith(MockitoExtension.class)
class GrpcTracePropagationTest {

    @Mock BuyingPowerCache buyingPowerCache;
    @Mock PriceCache priceCache;

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private Server server;
    private ManagedChannel channel;
    private ObservationRegistry registry;
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void setUp() throws Exception {
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
        OtelTracer tracer = new OtelTracer(otel.getTracer("test"), currentTraceContext, event -> { });
        OtelPropagator propagator = new OtelPropagator(otel.getPropagators(), otel.getTracer("test"));

        registry = ObservationRegistry.create();
        // first matching handler wins, as in Spring Boot's tracing auto-configuration
        registry.observationConfig().observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));

        lenient().when(priceCache.get("AAPL")).thenReturn(Optional.of(new BigDecimal("100.00")));
        RiskEvaluationService service = new RiskEvaluationService(buyingPowerCache, priceCache, new SimpleMeterRegistry());

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(new RiskCheckGrpcService(service),
                        new ObservationGrpcServerInterceptor(registry)))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
        tracerProvider.close();
    }

    @Test
    void serverSpanJoinsTheClientsTrace() {
        RiskCheckGrpc.RiskCheckBlockingStub stub = RiskCheckGrpc.newBlockingStub(
                ClientInterceptors.intercept(channel, new ObservationGrpcClientInterceptor(registry)));

        // an enclosing observation stands in for the HTTP request span of POST /api/orders
        Observation.createNotStarted("test.request", registry).observe(() ->
                stub.check(RiskCheckRequest.newBuilder()
                        .setAccountId("acc-1").setUserId("user-1").setTicker("AAPL")
                        .setSide(RiskCheckRequest.Side.BUY).setType(RiskCheckRequest.Type.MARKET)
                        .setQuantity("10").build()));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData client = spans.stream().filter(s -> s.getKind().name().equals("CLIENT")).findFirst().orElseThrow();
        SpanData server = spans.stream().filter(s -> s.getKind().name().equals("SERVER")).findFirst().orElseThrow();

        assertThat(server.getTraceId()).isEqualTo(client.getTraceId());
        assertThat(server.getParentSpanId()).isEqualTo(client.getSpanId());
    }
}

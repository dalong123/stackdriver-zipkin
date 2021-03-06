package com.google.cloud.trace.zipkin;

import com.google.common.collect.Sets;
import com.google.devtools.cloudtrace.v1.PatchTracesRequest;
import com.google.devtools.cloudtrace.v1.Trace;
import com.google.devtools.cloudtrace.v1.TraceServiceGrpc;
import com.google.devtools.cloudtrace.v1.TraceSpan;
import com.google.protobuf.Empty;
import io.grpc.Server;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static java.util.Collections.unmodifiableSet;

/**
 * Starts up a local Stackdriver Trace server, listening for GRPC requests on {@link #grpcURI}.
 *
 */
public class StackdriverMockServer extends TraceServiceGrpc.TraceServiceImplBase
{
  private static final Logger LOG = LoggerFactory.getLogger(StackdriverMockServer.class);

  static final SslContext CLIENT_SSL_CONTEXT ;
  static final SslContext SERVER_SSL_CONTEXT ;

  static
  {
    try
    {
      final SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
      CLIENT_SSL_CONTEXT = GrpcSslContexts.forClient().trustManager(cert.cert()).build();
      SERVER_SSL_CONTEXT = GrpcSslContexts.forServer(cert.certificate(), cert.privateKey()).build();
    }
    catch (CertificateException|SSLException e)
    {
      throw new Error(e);
    }
  }

  private final int port;
  private final Server server;

  private final Set<String> traceIds = Sets.newConcurrentHashSet();
  private final Set<Long> spanIds = Sets.newConcurrentHashSet();
  private CountDownLatch spanCountdown;

  public StackdriverMockServer(int port)
  {
    this.port = port;
    this.server = NettyServerBuilder.forPort(port).sslContext(SERVER_SSL_CONTEXT).addService(this).build();
  }

  @PostConstruct
  public void start() throws IOException
  {
    this.server.start();

    LOG.info("Started MOCK grpc server on 'localhost:{}'", port);
  }

  @PreDestroy
  public void stop() throws IOException
  {
    this.server.shutdownNow();
  }

  public void reset()
  {
    this.spanCountdown = null;
    this.traceIds.clear();
    this.spanIds.clear();
  }

  @Override
  public void patchTraces(PatchTracesRequest request, StreamObserver<Empty> responseObserver)
  {
    final List<Trace> tracesList = request.getTraces().getTracesList();
    for (Trace trace : tracesList)
    {
      for (TraceSpan span : trace.getSpansList())
      {
        this.spanIds.add(span.getSpanId());
        if (this.spanCountdown != null)
        {
          this.spanCountdown.countDown();
        }
      }
    }
    responseObserver.onNext(Empty.getDefaultInstance());
    responseObserver.onCompleted();
  }

  public void setSpanCountdown(CountDownLatch spanCountdown)
  {
    this.spanCountdown = spanCountdown;
  }

  public String grpcURI()
  {
    return String.format("%s:%s", "localhost", this.port);
  }

  public Set<Long> spanIds()
  {
    return unmodifiableSet(spanIds);
  }

  public Set<String> traceIds()
  {
    return unmodifiableSet(traceIds);
  }
}

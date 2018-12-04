/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.routeguide;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Message;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.routeguide.RouteGuideGrpc.RouteGuideBlockingStub;
import io.grpc.examples.routeguide.RouteGuideGrpc.RouteGuideStub;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sample client code that makes gRPC calls to the server.
 */
public class RouteGuideClient {
  private static final Logger logger = Logger.getLogger(RouteGuideClient.class.getName());

  private final ManagedChannel channel;
  private final RouteGuideBlockingStub blockingStub;
  private final RouteGuideStub asyncStub;

  private Random random = new Random();
  private TestHelper testHelper;

  /** Construct client for accessing RouteGuide server at {@code host:port}. */
  public RouteGuideClient(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port).usePlaintext());
  }

  /** Construct client for accessing RouteGuide server using the existing channel. */
  public RouteGuideClient(ManagedChannelBuilder<?> channelBuilder) {
    channel = channelBuilder.build();
    blockingStub = RouteGuideGrpc.newBlockingStub(channel);
    asyncStub = RouteGuideGrpc.newStub(channel);
  }

  public void shutdown() throws InterruptedException {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * 一个简单RPC
   * 客户端使用存根发送请求到服务器并等待响应返回。
   * Blocking unary call example.
   * Calls getFeature and prints the response.
   */
  public void getFeature(int lat, int lon) {
    info("*** GetFeature: lat={0} lon={1}", lat, lon);

    Point request = Point.newBuilder().setLatitude(lat).setLongitude(lon).build();

    Feature feature;
    try {
      feature = blockingStub.getFeature(request);
      if (testHelper != null) {
        testHelper.onMessage(feature);
      }
    } catch (StatusRuntimeException e) {
      warning("RPC failed: {0}", e.getStatus());
      if (testHelper != null) {
        testHelper.onRpcError(e);
      }
      return;
    }
    if (RouteGuideUtil.exists(feature)) {
      info("Found feature called \"{0}\" at {1}, {2}",
          feature.getName(),
          RouteGuideUtil.getLatitude(feature.getLocation()),
          RouteGuideUtil.getLongitude(feature.getLocation()));
    } else {
      info("Found no feature at {0}, {1}",
          RouteGuideUtil.getLatitude(feature.getLocation()),
          RouteGuideUtil.getLongitude(feature.getLocation()));
    }
  }

  /**
   * 服务器端流式RPC
   * 客户端读取返回的流，直到里面没有任何消息。
   * Blocking server-streaming example.
   * Calls listFeatures with a rectangle of interest. Prints each
   * response feature as it arrives.
   */
  public void listFeatures(int lowLat, int lowLon, int hiLat, int hiLon) {
    info("*** ListFeatures: lowLat={0} lowLon={1} hiLat={2} hiLon={3}", lowLat, lowLon, hiLat,
        hiLon);

    Rectangle request =
        Rectangle.newBuilder()
            .setLo(Point.newBuilder().setLatitude(lowLat).setLongitude(lowLon).build())
            .setHi(Point.newBuilder().setLatitude(hiLat).setLongitude(hiLon).build()).build();
    Iterator<Feature> features;
    try {
      features = blockingStub.listFeatures(request);
      for (int i = 1; features.hasNext(); i++) {
        Feature feature = features.next();
        info("Result #" + i + ": {0}", feature);
        if (testHelper != null) {
          testHelper.onMessage(feature);
        }
      }
    } catch (StatusRuntimeException e) {
      warning("RPC failed: {0}", e.getStatus());
      if (testHelper != null) {
        testHelper.onRpcError(e);
      }
    }
  }

  /**
   * 客户端流式RPC
   * 客户端写入一个消息序列并将其发送到服务器，同样也是使用流。
   * 一旦客户端完成写入消息，它等待服务器完成读取返回它的响应。
   * 通过在请求类型前指定 stream 关键字来指定一个客户端的流方法。
   *
   * Async client-streaming example. Sends {@code numPoints} randomly chosen points from {@code
   * features} with a variable delay in between. Prints the statistics when they are sent from the
   * server.
   */
  public void recordRoute(List<Feature> features, int numPoints) throws InterruptedException {
    info("*** RecordRoute");
    final CountDownLatch finishLatch = new CountDownLatch(1);
    StreamObserver<RouteSummary> responseObserver = new StreamObserver<RouteSummary>() {
      //覆写onNext() 方法，在服务器把RouteSummary写入到消息流时，打印出返回的信息。
      @Override
      public void onNext(RouteSummary summary) {
        info("Finished trip with {0} points. Passed {1} features. "
            + "Travelled {2} meters. It took {3} seconds.", summary.getPointCount(),
            summary.getFeatureCount(), summary.getDistance(), summary.getElapsedTime());
        if (testHelper != null) {
          testHelper.onMessage(summary);
        }
      }

      @Override
      public void onError(Throwable t) {
        warning("RecordRoute Failed: {0}", Status.fromThrowable(t));
        if (testHelper != null) {
          testHelper.onRpcError(t);
        }
        finishLatch.countDown();
      }

      // 覆写onCompleted()方法（在服务器完成自己的调用时调用）去设置SettableFuture，
      // 这样我们可以检查服务器是不是完成写入。
      @Override
      public void onCompleted() {
        info("Finished RecordRoute");
        finishLatch.countDown();
      }
    };

    //将StreamObserver传给异步存根的recordRoute()方法
    //拿到我们自己的StreamObserver请求观察者将Point发给服务器。
    StreamObserver<Point> requestObserver = asyncStub.recordRoute(responseObserver);
    try {
      // Send numPoints points randomly selected from the features list.
      for (int i = 0; i < numPoints; ++i) {
        int index = random.nextInt(features.size());
        Point point = features.get(index).getLocation();
        info("Visiting point {0}, {1}", RouteGuideUtil.getLatitude(point),
            RouteGuideUtil.getLongitude(point));
        //Send numPoints
        requestObserver.onNext(point);
        // Sleep for a bit before sending the next one.
        Thread.sleep(random.nextInt(1000) + 500);
        if (finishLatch.getCount() == 0) {
          // RPC completed or errored before we finished sending.
          // Sending further requests won't error, but they will just be thrown away.
          return;
        }
      }
    } catch (RuntimeException e) {
      // Cancel RPC
      requestObserver.onError(e);
      throw e;
    }
    // Mark the end of requests
    //一旦完成点的写入，我们使用请求观察者的onCompleted()
    //方法告诉gRPC我们已经完成了客户端的写入。
    requestObserver.onCompleted();

    // Receiving happens asynchronously
    if (!finishLatch.await(1, TimeUnit.MINUTES)) {
      warning("recordRoute can not finish within 1 minutes");
    }
  }

  /**
   * 双向流式RPC
   * 双方使用读写流去发送一个消息序列。两个流独立操作，
   * 因此客户端和服务器，可以以任意喜欢的顺序读写。
   * 比如， 服务器可以在写入响应前等待接收所有的客户端消息，
   * 或者可以交替的读取和写入消息，或者其他读写的组合。
   * 每个流中的消息顺序被预留。
   * 可以通过在请求和响应前加stream关键字去制定方法的类型。
   *
   * Bi-directional example, which can only be asynchronous. Send some chat messages, and print any
   * chat messages that are sent from the server.
   */
  public CountDownLatch routeChat() {
    info("*** RouteChat");
    final CountDownLatch finishLatch = new CountDownLatch(1);
    StreamObserver<RouteNote> requestObserver =
        asyncStub.routeChat(new StreamObserver<RouteNote>() {
          @Override
          public void onNext(RouteNote note) {
            info("Got message \"{0}\" at {1}, {2}", note.getMessage(), note.getLocation()
                .getLatitude(), note.getLocation().getLongitude());
            if (testHelper != null) {
              testHelper.onMessage(note);
            }
          }

          @Override
          public void onError(Throwable t) {
            warning("RouteChat Failed: {0}", Status.fromThrowable(t));
            if (testHelper != null) {
              testHelper.onRpcError(t);
            }
            finishLatch.countDown();
          }

          @Override
          public void onCompleted() {
            info("Finished RouteChat");
            finishLatch.countDown();
          }
        });

    try {
      RouteNote[] requests =
          {newNote("First message", 0, 0), newNote("Second message", 0, 1),
              newNote("Third message", 1, 0), newNote("Fourth message", 1, 1)};

      for (RouteNote request : requests) {
        info("Sending message \"{0}\" at {1}, {2}", request.getMessage(), request.getLocation()
            .getLatitude(), request.getLocation().getLongitude());
        requestObserver.onNext(request);
      }
    } catch (RuntimeException e) {
      // Cancel RPC
      requestObserver.onError(e);
      throw e;
    }
    // Mark the end of requests
    requestObserver.onCompleted();

    // return the latch while receiving happens asynchronously
    return finishLatch;
  }

  /** Issues several different requests and then exits. */
  public static void main(String[] args) throws InterruptedException {
    List<Feature> features;
    try {
      features = RouteGuideUtil.parseFeatures(RouteGuideUtil.getDefaultFeaturesFile());
    } catch (IOException ex) {
      ex.printStackTrace();
      return;
    }

    RouteGuideClient client = new RouteGuideClient("localhost", 8980);
    try {
      // Looking for a valid feature
      client.getFeature(409146138, -746188906);

      // Feature missing.
      client.getFeature(0, 0);

      // Looking for features between 40, -75 and 42, -73.
      client.listFeatures(400000000, -750000000, 420000000, -730000000);

      // Record a few randomly selected points from the features file.
      client.recordRoute(features, 10);

      // Send and receive some notes.
      CountDownLatch finishLatch = client.routeChat();

      if (!finishLatch.await(1, TimeUnit.MINUTES)) {
        client.warning("routeChat can not finish within 1 minutes");
      }
    } finally {
      client.shutdown();
    }
  }

  private void info(String msg, Object... params) {
    logger.log(Level.INFO, msg, params);
  }

  private void warning(String msg, Object... params) {
    logger.log(Level.WARNING, msg, params);
  }

  private RouteNote newNote(String message, int lat, int lon) {
    return RouteNote.newBuilder().setMessage(message)
        .setLocation(Point.newBuilder().setLatitude(lat).setLongitude(lon).build()).build();
  }

  /**
   * Only used for unit test, as we do not want to introduce randomness in unit test.
   */
  @VisibleForTesting
  void setRandom(Random random) {
    this.random = random;
  }

  /**
   * Only used for helping unit test.
   */
  @VisibleForTesting
  interface TestHelper {
    /**
     * Used for verify/inspect message received from server.
     */
    void onMessage(Message message);

    /**
     * Used for verify/inspect error received from server.
     */
    void onRpcError(Throwable exception);
  }

  @VisibleForTesting
  void setTestHelper(TestHelper testHelper) {
    this.testHelper = testHelper;
  }
}

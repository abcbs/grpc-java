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

import static java.lang.Math.atan2;
import static java.lang.Math.cos;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.sin;
import static java.lang.Math.sqrt;
import static java.lang.Math.toRadians;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A sample gRPC server that serve the RouteGuide (see route_guide.proto) service.
 */
public class RouteGuideServer {
  private static final Logger logger = Logger.getLogger(RouteGuideServer.class.getName());

  private final int port;
  private final Server server;

  public RouteGuideServer(int port) throws IOException {
    this(port, RouteGuideUtil.getDefaultFeaturesFile());
  }

  /** Create a RouteGuide server listening on {@code port} using {@code featureFile} database. */
  public RouteGuideServer(int port, URL featureFile) throws IOException {
    this(ServerBuilder.forPort(port), port, RouteGuideUtil.parseFeatures(featureFile));
  }

  /** Create a RouteGuide server using serverBuilder as a base and features as data. */
  public RouteGuideServer(ServerBuilder<?> serverBuilder, int port, Collection<Feature> features) {
    this.port = port;
    server = serverBuilder.addService(new RouteGuideService(features))
        .build();
  }

  /** Start serving requests. */
  public void start() throws IOException {
    server.start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may has been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        RouteGuideServer.this.stop();
        System.err.println("*** server shut down");
      }
    });
  }

  /** Stop serving requests and shutdown resources. */
  public void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /**
   * Main method.  This comment makes the linter happy.
   */
  public static void main(String[] args) throws Exception {
    RouteGuideServer server = new RouteGuideServer(8980);
    server.start();
    server.blockUntilShutdown();
  }

  /**
   *
   * 要定义一个服务，你必须在你的.proto文件中指定service
   * Our implementation of RouteGuide service.
   *
   * <p>See route_guide.proto for details of the methods.
   */
  private static class RouteGuideService extends RouteGuideGrpc.RouteGuideImplBase {
    private final Collection<Feature> features;
    private final ConcurrentMap<Point, List<RouteNote>> routeNotes =
        new ConcurrentHashMap<Point, List<RouteNote>>();

    RouteGuideService(Collection<Feature> features) {
      this.features = features;
    }

    /**
     * 一个简单RPC
     * 客户端使用存根发送请求到服务器并等待响应返回。
     * Gets the {@link Feature} at the requested {@link Point}. If no feature at that location
     * exists, an unnamed feature is returned at the provided location.
     *
     * @param request the requested location for the feature.
     * @param responseObserver the observer that will receive the feature at the requested point.
     */
    @Override
    public void getFeature(Point request, StreamObserver<Feature> responseObserver) {
      //使用应答观察者的 onNext() 方法返回 Feature。
      responseObserver.onNext(checkFeature(request));
      //使用应答观察者的 onCompleted() 方法来指出我们已经完成了和 RPC的交互。
      responseObserver.onCompleted();
    }

    /**
     * 一个服务器端流式:
     * 客户端发送请求到服务器，拿到一个流去读取返回的消息序列。 客户端读取返回的流，直到里面没有任何消息。
     * 从例子中可以看出，通过在 响应 类型前插入 stream 关键字，可以指定一个服务器端的流方法。
     *
     * Gets all features contained within the given bounding {@link Rectangle}.
     *
     * @param request the bounding rectangle for the requested features.
     * @param responseObserver the observer that will receive the features.
     */
    @Override
    public void listFeatures(Rectangle request, StreamObserver<Feature> responseObserver) {
      int left = min(request.getLo().getLongitude(), request.getHi().getLongitude());
      int right = max(request.getLo().getLongitude(), request.getHi().getLongitude());
      int top = max(request.getLo().getLatitude(), request.getHi().getLatitude());
      int bottom = min(request.getLo().getLatitude(), request.getHi().getLatitude());

      for (Feature feature : features) {
        if (!RouteGuideUtil.exists(feature)) {
          continue;
        }

        int lat = feature.getLocation().getLatitude();
        int lon = feature.getLocation().getLongitude();
        if (lon >= left && lon <= right && lat >= bottom && lat <= top) {
          //使用onNext()方法轮流往响应观察者写入
          responseObserver.onNext(feature);
        }
      }
      //使用响应观察者的onCompleted()方法去告诉gRPC写入应答已完成
      responseObserver.onCompleted();
    }

    /**
     * 一个客户端流式
     * 客户端写入一个消息序列并将其发送到服务器，同样也是使用流。一旦客户端完成写入消息，
     * 它等待服务器完成读取返回它的响应。通过在请求类型前指定stream关键字来指定一个客户端的流方法。
     *
     * Gets a stream of points, and responds with statistics about the "trip": number of points,
     * number of known features visited, total distance traveled, and total time spent.
     *
     * @param responseObserver an observer to receive the response summary.
     * @return an observer to receive the requested route points.
     */
    @Override
    public StreamObserver<Point> recordRoute(final StreamObserver<RouteSummary> responseObserver) {
      return new StreamObserver<Point>() {//从客户端拿到一个 Point 的流，并且返回一个包括它们路径的信息
        int pointCount;
        int featureCount;
        int distance;
        Point previous;
        final long startTime = System.nanoTime();
        //覆写了onNext()方法，每次客户端写入一个Point到消息流时，拿到特性和其它信息。
        @Override
        public void onNext(Point point) {
          pointCount++;
          if (RouteGuideUtil.exists(checkFeature(point))) {
            featureCount++;
          }
          // For each point after the first, add the incremental distance from the previous point to
          // the total distance value.
          if (previous != null) {
            distance += calcDistance(previous, point);
          }
          previous = point;
        }

        @Override
        public void onError(Throwable t) {
          logger.log(Level.WARNING, "recordRoute cancelled");
        }

        //覆写了onCompleted()方法（在客户端结束写入消息时调用），用来填充和构建我们的RouteSummary。
        @Override
        public void onCompleted() {
          long seconds = NANOSECONDS.toSeconds(System.nanoTime() - startTime);
          //用RouteSummary调用方法自己的的响应观察者的onNext()，
          responseObserver.onNext(RouteSummary.newBuilder().setPointCount(pointCount)
              .setFeatureCount(featureCount).setDistance(distance)
              .setElapsedTime((int) seconds).build());
          //调用它的onCompleted()方法，结束服务器端的调用。
          responseObserver.onCompleted();
        }
      };
    }

    /**
     * 一个双向流式RPC
     * 双方使用读写流去发送一个消息序列。两个流独立操作，因此客户端和服务器 可以以任意喜欢的顺序读写。
     * 比如， 服务器可以在写入响应前等待接收所有的客户端消息，或者可以交替 的读取和写入消息，或者其他读写的组合。
     * 每个流中的消息顺序被预留。可以通过在请求和响应前加 stream 关键字去制定方法的类型。
     *
     * 每一端都会按照它们写入的顺序拿到另一端的消息，客户端和服务器都可以任意顺序读写——流的操作是互不依赖的。
     * Receives a stream of message/location pairs, and responds with a stream of all previous
     * messages at each of those locations.
     *
     * @param responseObserver an observer to receive the stream of previous messages.
     * @return an observer to handle requested message/location pairs.
     */
    @Override
    public StreamObserver<RouteNote> routeChat(final StreamObserver<RouteNote> responseObserver) {
      return new StreamObserver<RouteNote>() {
        @Override
        public void onNext(RouteNote note) {
          List<RouteNote> notes = getOrCreateNotes(note.getLocation());
          // Respond with all previous notes at this location.
          for (RouteNote prevNote : notes.toArray(new RouteNote[0])) {
            //在客户端仍然写入消息到它们的消息流，通过我们方法的应答观察者返回值。
            //响应客户端
            responseObserver.onNext(prevNote);
          }

          // Now add the new note to the list
          notes.add(note);
        }

        @Override
        public void onError(Throwable t) {
          logger.log(Level.WARNING, "routeChat cancelled");
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }

    /**
     * Get the notes list for the given location. If missing, create it.
     */
    private List<RouteNote> getOrCreateNotes(Point location) {
      List<RouteNote> notes = Collections.synchronizedList(new ArrayList<RouteNote>());
      List<RouteNote> prevNotes = routeNotes.putIfAbsent(location, notes);
      return prevNotes != null ? prevNotes : notes;
    }

    /**
     * Gets the feature at the given point.
     *
     * @param location the location to check.
     * @return The feature object at the point. Note that an empty name indicates no feature.
     */
    private Feature checkFeature(Point location) {
      for (Feature feature : features) {
        if (feature.getLocation().getLatitude() == location.getLatitude()
            && feature.getLocation().getLongitude() == location.getLongitude()) {
          return feature;
        }
      }

      // No feature was found, return an unnamed feature.
      return Feature.newBuilder().setName("").setLocation(location).build();
    }

    /**
     * Calculate the distance between two points using the "haversine" formula.
     * The formula is based on http://mathforum.org/library/drmath/view/51879.html.
     *
     * @param start The starting point
     * @param end The end point
     * @return The distance between the points in meters
     */
    private static int calcDistance(Point start, Point end) {
      int r = 6371000; // earth radius in meters
      double lat1 = toRadians(RouteGuideUtil.getLatitude(start));
      double lat2 = toRadians(RouteGuideUtil.getLatitude(end));
      double lon1 = toRadians(RouteGuideUtil.getLongitude(start));
      double lon2 = toRadians(RouteGuideUtil.getLongitude(end));
      double deltaLat = lat2 - lat1;
      double deltaLon = lon2 - lon1;

      double a = sin(deltaLat / 2) * sin(deltaLat / 2)
          + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2);
      double c = 2 * atan2(sqrt(a), sqrt(1 - a));

      return (int) (r * c);
    }
  }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdds.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ServiceException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import org.apache.hadoop.hdds.tracing.TestSpanCollector;
import org.apache.hadoop.hdds.utils.ProtocolMessageMetrics;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tests the SERVER span created by {@link OzoneProtocolMessageDispatcher}.
 */
public class TestOzoneProtocolMessageDispatcher {

  private final OzoneProtocolMessageDispatcher<String, String, TestType> dispatcher =
      new OzoneProtocolMessageDispatcher<>("test", ProtocolMessageMetrics.create("test", "test", TestType.class),
          LoggerFactory.getLogger(TestOzoneProtocolMessageDispatcher.class));

  @Test
  public void testSuccessfulRequestLeavesSpanUnset() throws ServiceException {
    try (TestSpanCollector collector = TestSpanCollector.install("test")) {
      assertEquals("response", dispatcher.processRequest("request", request -> "response", TestType.SUCCEED, null));

      SpanData span = finishedSpan(collector, TestType.SUCCEED.name());
      assertEquals(SpanKind.SERVER, span.getKind());
      assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
      assertEquals(0, span.getEvents().size());
    }
  }

  @Test
  public void testThrownExceptionMarksSpanError() {
    try (TestSpanCollector collector = TestSpanCollector.install("test")) {
      assertThrows(ServiceException.class, () -> dispatcher.processRequest("request", request -> {
        throw new ServiceException("request failed");
      }, TestType.FAIL, null));

      SpanData span = finishedSpan(collector, TestType.FAIL.name());
      assertEquals(SpanKind.SERVER, span.getKind());
      assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
      assertEquals("request failed", span.getStatus().getDescription());
      List<EventData> events = span.getEvents();
      assertEquals(1, events.size());
      assertEquals("exception", events.get(0).getName());
      assertEquals(ServiceException.class.getName(),
          events.get(0).getAttributes().get(AttributeKey.stringKey("exception.type")));
    }
  }

  /** @return the only collected span named {@code name}; fails the test if there is not exactly one. */
  private static SpanData finishedSpan(TestSpanCollector collector, String name) {
    List<ReadableSpan> spans = collector.named(name);
    assertEquals(1, spans.size(), () -> "spans named " + name + ": " + collector.getSpans());
    return spans.get(0).toSpanData();
  }

  private enum TestType {
    SUCCEED, FAIL
  }
}

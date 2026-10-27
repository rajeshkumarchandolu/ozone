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

package org.apache.hadoop.hdds.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link TraceAllMethod}.
 */
public class TestTraceAllMethod {

  @Test
  public void testUnknownMethod() {
    TraceAllMethod<Service> subject = new TraceAllMethod<>(new ServiceImpl(),
        "service");
    assertThrows(NoSuchMethodException.class, () -> subject.invoke(null,
        ServiceImpl.class.getMethod("no such method"), new Object[]{}));
  }

  @Test
  public void testDefaultMethod() throws Throwable {
    TraceAllMethod<Service> subject = new TraceAllMethod<>(new ServiceImpl(),
        "service");

    assertEquals("Hello default", subject.invoke(null,
        ServiceImpl.class.getMethod("defaultMethod"), new Object[]{}));
  }

  @Test
  public void testTracedMethodExceptionMarksSpanError() {
    try (TestSpanCollector collector = TestSpanCollector.install("test")) {
      TraceAllMethod<Service> subject = new TraceAllMethod<>(new ServiceImpl(), "service");

      IOException thrown = assertThrows(IOException.class,
          () -> subject.invoke(null, ServiceImpl.class.getMethod("failingMethod"), new Object[]{}));
      assertEquals("traced failure", thrown.getMessage());

      List<ReadableSpan> spans = collector.named("service.failingMethod");
      assertEquals(1, spans.size(), () -> "spans: " + collector.getSpans());
      SpanData data = spans.get(0).toSpanData();
      assertEquals(SpanKind.CLIENT, data.getKind());
      assertEquals(StatusCode.ERROR, data.getStatus().getStatusCode());
      assertEquals("traced failure", data.getStatus().getDescription());
      assertEquals(1, data.getEvents().size());
      assertEquals("exception", data.getEvents().get(0).getName());
      assertEquals(IOException.class.getName(),
          data.getEvents().get(0).getAttributes().get(AttributeKey.stringKey("exception.type")));
    }
  }

  @Test
  public void testGetterNotTraced() throws Throwable {
    try (TestSpanCollector collector = TestSpanCollector.install("test")) {
      TraceAllMethod<Service> subject = new TraceAllMethod<>(new ServiceImpl(), "service");

      assertEquals("service", subject.invoke(null, ServiceImpl.class.getMethod("getName"), new Object[]{}));
      assertEquals(true, subject.invoke(null, ServiceImpl.class.getMethod("isReady"), new Object[]{}));
      assertEquals("Hello world",
          subject.invoke(null, ServiceImpl.class.getMethod("getGreeting", String.class), new Object[]{"world"}));
      assertEquals("normal", subject.invoke(null, ServiceImpl.class.getMethod("normalMethod"), new Object[]{}));

      assertThat(collector.named("service.getName")).isEmpty();
      assertThat(collector.named("service.isReady")).isEmpty();
      // A get method with parameters is not a getter.
      assertEquals(1, collector.named("service.getGreeting").size(), () -> "spans: " + collector.getSpans());
      assertEquals(1, collector.named("service.normalMethod").size(), () -> "spans: " + collector.getSpans());
    }
  }

  /**
   * Example interface for testing {@link TraceAllMethod}.
   */
  public interface Service {
    default String defaultMethod() {
      return otherMethod("default");
    }

    String localMethod();

    String skippedMethod();

    void throwingMethod() throws IOException;

    void failingMethod() throws IOException;

    String normalMethod();

    String otherMethod(String name);

    String getName();

    boolean isReady();

    String getGreeting(String name);
  }

  /**
   * Example implementation class for testing {@link TraceAllMethod}.
   */
  public static class ServiceImpl implements Service {

    private boolean spanActive = false;
    private SpanKind spanKind;

    public SpanKind lastSpanKind() {
      return spanKind;
    }

    @Override
    public String otherMethod(String name) {
      return "Hello " + name;
    }

    @Override
    @SkipTracing
    public String skippedMethod() {
      this.spanActive = Span.current().getSpanContext().isValid();
      return "skipped";
    }

    @Override
    @SkipTracing
    public void throwingMethod() throws IOException {
      this.spanActive = Span.current().getSpanContext().isValid();
      throw new IOException("Original Exception");
    }

    @Override
    public void failingMethod() throws IOException {
      throw new IOException("traced failure");
    }

    @Override
    public String getName() {
      return "service";
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public String getGreeting(String name) {
      return otherMethod(name);
    }

    @Override
    public String normalMethod() {
      this.spanActive = Span.current().getSpanContext().isValid();
      return "normal";
    }

    @Override
    @InternalSpanKind
    public String localMethod() {
      Span span = Span.current();
      this.spanActive = span.getSpanContext().isValid();
      if (spanActive) {
        this.spanKind = ((ReadableSpan) span).getKind();
      }
      return "local";
    }

    public boolean wasSpanActive() {
      return spanActive;
    }
  }
}

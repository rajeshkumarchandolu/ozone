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

package org.apache.hadoop.hdds.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandRequestProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandResponseProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.DatanodeBlockID;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.GetBlockRequestProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Type;
import org.apache.hadoop.hdds.scm.pipeline.MockPipeline;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.tracing.SpanAttributeKeys;
import org.apache.hadoop.hdds.tracing.TestSpanCollector;
import org.junit.jupiter.api.Test;

/**
 * Tests the spans {@link XceiverClientGrpc} creates for each datanode attempt of a command.
 */
class TestXceiverClientGrpcTracing {

  private static final AttributeKey<String> DATANODE = AttributeKey.stringKey(SpanAttributeKeys.DATANODE);

  @Test
  void failedDatanodeAttemptIsErrorWithoutMarkingCommandSpan() throws IOException {
    Pipeline pipeline = MockPipeline.createRatisPipeline();
    List<DatanodeDetails> tried = new ArrayList<>();
    List<String> sentTraceIds = new ArrayList<>();
    List<String> currentSpanIds = new ArrayList<>();
    try (TestSpanCollector collector = TestSpanCollector.install("test");
         XceiverClientGrpc client = new XceiverClientGrpc(pipeline, new OzoneConfiguration()) {
           @Override
           public XceiverClientReply sendCommandAsync(ContainerCommandRequestProto request, DatanodeDetails dn)
               throws IOException {
             tried.add(dn);
             sentTraceIds.add(request.getTraceID());
             currentSpanIds.add(Span.current().getSpanContext().getSpanId());
             if (tried.size() == 1) {
               throw new IOException("Failed " + dn);
             }
             return successReply();
           }
         }) {
      client.sendCommand(getBlockRequest(pipeline.getFirstNode()));

      List<SpanData> attempts = spansNamed(collector, "Client.sendToDatanode");
      assertEquals(2, attempts.size());
      assertEquals(StatusCode.ERROR, attempts.get(0).getStatus().getStatusCode());
      assertEquals(StatusCode.UNSET, attempts.get(1).getStatus().getStatusCode());

      List<SpanData> command = spansNamed(collector, "XceiverClientGrpc.GetBlock");
      assertEquals(1, command.size());
      assertEquals(StatusCode.UNSET, command.get(0).getStatus().getStatusCode());

      for (int i = 0; i < attempts.size(); i++) {
        SpanData attempt = attempts.get(i);
        assertEquals(command.get(0).getSpanId(), attempt.getParentSpanId());
        assertEquals(tried.get(i).toString(), attempt.getAttributes().get(DATANODE));
        assertEquals(attempt.getSpanId(), currentSpanIds.get(i));
        // The request sent to each datanode carries that attempt as the parent.
        assertThat(sentTraceIds.get(i)).contains(attempt.getSpanId());
      }
    }
  }

  private static List<SpanData> spansNamed(TestSpanCollector collector, String name) {
    return collector.named(name).stream().map(ReadableSpan::toSpanData).collect(Collectors.toList());
  }

  private static ContainerCommandRequestProto getBlockRequest(DatanodeDetails dn) {
    return ContainerCommandRequestProto.newBuilder()
        .setCmdType(Type.GetBlock)
        .setContainerID(1)
        .setDatanodeUuid(dn.getUuidString())
        .setGetBlock(GetBlockRequestProto.newBuilder()
            .setBlockID(DatanodeBlockID.newBuilder().setContainerID(1).setLocalID(1).build()))
        .build();
  }

  private static XceiverClientReply successReply() {
    return new XceiverClientReply(CompletableFuture.completedFuture(ContainerCommandResponseProto.newBuilder()
        .setCmdType(Type.GetBlock)
        .setResult(Result.SUCCESS)
        .build()));
  }
}

/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import com.google.protobuf.UnsafeByteOperations;
import io.grpc.Detachable;
import io.grpc.HasByteBuffer;
import io.grpc.KnownLength;
import io.grpc.MethodDescriptor.PrototypeMarshaller;
import io.grpc.Status;
import io.grpc.protobuf.lite.ProtoLiteUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom gRPC marshaller to use zero memory copy feature of gRPC when deserializing messages. This
 * achieves zero-copy by deserializing proto messages pointing to the buffers in the input stream to
 * avoid memory copy so stream should live as long as the message can be referenced. Hence, it
 * exposes the input stream to applications (through popStream) and applications are responsible to
 * close it when it's no longer needed. Otherwise, it'd cause memory leak.
 */
class ZeroCopyMessageMarshaller<T extends MessageLite> implements PrototypeMarshaller<T> {
  private final Map<T, InputStream> unclosedStreams =
      Collections.synchronizedMap(new IdentityHashMap<>());
  private final Parser<T> parser;
  private final PrototypeMarshaller<T> marshaller;

  @SuppressWarnings("unchecked")
  ZeroCopyMessageMarshaller(T defaultInstance) {
    parser = (Parser<T>) defaultInstance.getParserForType();
    marshaller = (PrototypeMarshaller<T>) ProtoLiteUtils.marshaller(defaultInstance);
  }

  @Override
  public Class<T> getMessageClass() {
    return marshaller.getMessageClass();
  }

  @Override
  public T getMessagePrototype() {
    return marshaller.getMessagePrototype();
  }

  @Override
  public InputStream stream(T value) {
    return marshaller.stream(value);
  }

  @Override
  public T parse(InputStream stream) {
    try {
      if (stream instanceof KnownLength
          && stream instanceof Detachable
          && stream instanceof HasByteBuffer
          && ((HasByteBuffer) stream).byteBufferSupported()) {
        int size = stream.available();
        // Stream is now detached here and should be closed later.
        InputStream detachedStream = ((Detachable) stream).detach();
        try {
          // This mark call is to keep buffer while traversing buffers using skip.
          detachedStream.mark(size);
          List<ByteString> byteStrings = new ArrayList<>();
          while (detachedStream.available() != 0) {
            ByteBuffer buffer = ((HasByteBuffer) detachedStream).getByteBuffer();
            byteStrings.add(UnsafeByteOperations.unsafeWrap(buffer));
            detachedStream.skip(buffer.remaining());
          }
          detachedStream.reset();
          CodedInputStream codedInputStream = ByteString.copyFrom(byteStrings).newCodedInput();
          codedInputStream.enableAliasing(true);
          codedInputStream.setSizeLimit(Integer.MAX_VALUE);
          // fast path (no memory copy)
          T message;
          try {
            message = parseFrom(codedInputStream);
          } catch (InvalidProtocolBufferException ipbe) {
            throw Status.INTERNAL
                .withDescription("Invalid protobuf byte sequence")
                .withCause(ipbe)
                .asRuntimeException();
          }
          unclosedStreams.put(message, detachedStream);
          detachedStream = null;
          return message;
        } finally {
          if (detachedStream != null) {
            detachedStream.close();
          }
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    // slow path
    return marshaller.parse(stream);
  }

  private T parseFrom(CodedInputStream stream) throws InvalidProtocolBufferException {
    T message = parser.parseFrom(stream);
    try {
      stream.checkLastTagWas(0);
      return message;
    } catch (InvalidProtocolBufferException e) {
      e.setUnfinishedMessage(message);
      throw e;
    }
  }

  /**
   * Application needs to call this function to get the stream for the message and call
   * stream.close() function to return it to the pool.
   */
  public InputStream popStream(T message) {
    return unclosedStreams.remove(message);
  }

  /** Application needs to call stream.close() function to all streams returned. */
  public List<InputStream> popAllStreams() {
    ArrayList<InputStream> streams = new ArrayList<>(unclosedStreams.values());
    unclosedStreams.clear();
    return streams;
  }
}

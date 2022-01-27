/*
 * Copyright (c) 2012, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package demo;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.junit.jupiter.api.Test;

public class TestDemoInstrument {
    private static final HostAccess HOST_ACCESS = HostAccess.newBuilder(HostAccess.EXPLICIT)
    .allowIterableAccess(true)
    .allowIteratorAccess(true)
    .allowBufferAccess(true)
    .allowArrayAccess(true)
    .allowListAccess(true)
    .allowMapAccess(true)
    .build();

  @Test
  public void testContextClose() throws Exception {
    final Engine engine = Engine.newBuilder()
        .option(DemoInstrument.ID + ".enabled", "true")
        .build();

    try (Context context = newContext(engine)) {
      final JsTest jsTest = new JsTest(context);
      context.getBindings("js").putMember("JsTest", jsTest);
      context.eval("js", """
          async function foo() {
            return JSON.parse(await JsTest.test());
          }
        """);
      final Value promise = context.getBindings("js").getMember("foo").execute();
      final CountDownLatch latch = new CountDownLatch(1);
      final JsPromiseCatcher result = new JsPromiseCatcher(latch, "then");
      promise.invokeMember("then", result);
      promise.invokeMember("catch", new JsPromiseCatcher(latch, "catch"));
      System.out.println(Thread.currentThread() + ": " + promise);

      latch.await();
    }
  }

  public static final class JsPromiseCatcher implements Consumer<Value> {
    private final CountDownLatch latch;
    private final String name;
    private Value result;

    private JsPromiseCatcher(final CountDownLatch latcher, final String name) {
      this.latch = latcher;
      this.name = name;
    }

    @Override
    @HostAccess.Export
    public void accept(final Value value) {
      System.out.println(Thread.currentThread() + ": " + name + ".accept(): " + value);
      result = value;
      dumpResult();
      latch.countDown();
    }

    private void dumpResult() {
      System.out.println(Thread.currentThread() + ": " + name + ".dumpResult(): " + result);
      for (final String key: result.getMemberKeys()) {
        System.out.println(" -> " + key + " -> " + result.getMember(key));
      }
    }
  }

  private Context newContext(final Engine engine) {
    return Context.newBuilder("js")
      // configure host access
      .allowHostClassLoading(false)
      .allowHostAccess(HOST_ACCESS)
      // configure js builtin
      .allowExperimentalOptions(true)
      .option("js.strict", "true")
      .option("js.console", "false")
      .option("js.print", "false")
      .option("js.load", "false")
      .option("js.load-from-url", "false")
      .option("js.polyglot-builtin", "false")
      .engine(engine)
      .build();
  }

  public static class JsTest {
    private final Context jsEngine;

    private JsTest(final Context jsEngine) {
      this.jsEngine = jsEngine;
    }

    @HostAccess.Export
    public Value test() throws IOException, InterruptedException, URISyntaxException {
      final CompletableFuture<String> promise = new CompletableFuture<>();
      HttpClient.newBuilder().build().sendAsync(HttpRequest.newBuilder()
        .uri(new URI("http://worldclockapi.com/api/json/utc/now"))
        .build(), BodyHandlers.ofString()).whenComplete((result, except) -> {
        if (except != null) {
          promise.completeExceptionally(except);
        } else {
          promise.complete(result.body());
        }
      });
      return newPromise(promise);
    }

    private Value newPromise(final CompletableFuture<String> future) {
      final Value promiseConstructor = jsEngine.getBindings("js").getMember("Promise");
      return promiseConstructor.newInstance((ProxyExecutable) arguments -> {
        final Value resolve = arguments[0];
        final Value reject = arguments[1];
        future.whenComplete((result, ex) -> {
          if (result != null) {
            resolve.execute(result);
          } else {
            reject.execute(ex);
          }
        });
        return null;
      });
    }
  }
}

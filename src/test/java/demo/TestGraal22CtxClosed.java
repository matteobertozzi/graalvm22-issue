package demo;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.PolyglotException.StackFrame;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.junit.jupiter.api.Test;

public class TestGraal22CtxClosed {
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
    final Engine engine = Engine.newBuilder().build();

    for (int i = 0; i < 20; ++i) {
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
        Thread.sleep(1500);
        System.out.println(promise);
        System.out.println(context.eval("js", "10 + 5"));
        result.dumpResult();
      }
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

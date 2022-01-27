package demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import com.oracle.truffle.api.Option;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.instrumentation.ContextsListener;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Registration;
import com.oracle.truffle.api.nodes.LanguageInfo;

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;
import org.graalvm.options.OptionValues;

@Registration(id = DemoInstrument.ID, name = "Sandbox for Scripts", version = "0.1", services = {
    DemoInstrument.class, TruffleInstrument.Env.class })
public final class DemoInstrument extends TruffleInstrument {

  @Option(name = "enabled", help = "Enable Sandbox for scripts (default: false).", category = OptionCategory.USER, stability = OptionStability.STABLE)
  static final OptionKey<Boolean> ENABLED = new OptionKey<>(false);

  @Option(name = "instance.id", help = "Set the instance identifier for the engine", category = OptionCategory.USER, stability = OptionStability.STABLE)
  static final OptionKey<String> INSTANCE_ID = new OptionKey<>("");

  @Option(name = "heap.limit", help = "Set the heap limit for this engine (default: no limit).", category = OptionCategory.USER, stability = OptionStability.STABLE)
  static final OptionKey<Long> HEAP_LIMIT = new OptionKey<>(-1L);

  public static final String ID = "simpletool";

  private static final Demo Demo = new Demo();

  @Override
  protected void onCreate(final Env env) {
    final OptionValues options = env.getOptions();
    System.out.println("CREATE " + env + " " + ENABLED.getValue(options));
    if (!ENABLED.getValue(options)) return;

    // Enable the instrument
    Demo.start(env);

    env.registerService(this);
    env.registerService(env);
  }

  @Override
  protected void onDispose(final Env env) {
    Demo.stop(env);
  }

  @Override
  protected OptionDescriptors getOptionDescriptors() {
    return new DemoInstrumentOptionDescriptors();
  }

  private static final class Demo {
    private final ConcurrentHashMap<Env, EnvListener> activeEnvs = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ReentrantLock lock = new ReentrantLock();
    private Thread thread;

    private void start(final Env env) {
      lock.lock();
      try {
        activeEnvs.put(env, new EnvListener(env));

        // run the checker thread
        if (thread == null) {
          System.out.println("Startup Demo");
          running.set(true);
          thread = new Thread(this::run, "Demo");
          thread.start();
        }
      } finally {
        lock.unlock();
      }
    }

    private void stop(final Env env) {
      System.out.println("close env: " + env);
      lock.lock();
      try {
        final EnvListener listener = this.activeEnvs.remove(env);
        listener.dispose();

        // stop the checker thread
        if (this.activeEnvs.isEmpty()) {
          System.out.println("Shutdown Demo");
          running.set(false);
          if (thread != null) {
            try {
              thread.join();
            } catch (final InterruptedException e) {
              // no-op
            }
            thread = null;
          }
        }
      } finally {
        lock.unlock();
      }
    }

    private void run() {
      final long HEAP_CHECK_DELAY = TimeUnit.SECONDS.toNanos(1);
      long lastRun = System.nanoTime();
      while (running.get()) {
        try {
          if (activeEnvs.isEmpty()) {
            Thread.sleep(1000);
            continue;
          }

          long now = System.nanoTime();
          final long delay = (now - lastRun);
          if (delay > HEAP_CHECK_DELAY) {
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(delay));
            now = System.nanoTime();
          }

          lastRun = now;
          for (final EnvListener envListener: this.activeEnvs.values()) {
            envListener.updateStats();
          }
        } catch (final Throwable e) {
          e.printStackTrace();
        }
      }
      System.out.println("Checker thread is shutting down");
    }
  }

  // ==========================================================================================
  //  Context Listeners related
  // ==========================================================================================
  private static final class EnvListener implements ContextsListener {
    private final ArrayList<EventBinding<EnvListener>> bindings = new ArrayList<>();
    private final ConcurrentHashMap<TruffleContext, ContextStats> contexts = new ConcurrentHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Env env;

    private EnvListener(final Env env) {
      this.env = env;

      final Instrumenter instrumenter = env.getInstrumenter();
      bindings.add(instrumenter.attachContextsListener(this, true));
    }

    public void dispose() {
      stopped.set(true);
      for (final EventBinding<EnvListener> binding: this.bindings) {
        binding.dispose();
      }
      this.bindings.clear();
    }

    @Override
    public void onContextCreated(final TruffleContext context) {
      contexts.put(context, new ContextStats());
    }

    @Override
    public void onLanguageContextCreated(final TruffleContext context, final LanguageInfo language) {
      // no-op
    }

    @Override
    public void onLanguageContextInitialized(final TruffleContext context, final LanguageInfo language) {
      updateContextStats(context);
    }

    @Override
    public void onLanguageContextFinalized(final TruffleContext context, final LanguageInfo language) {
      updateContextStats(context);
    }

    @Override
    public void onLanguageContextDisposed(final TruffleContext context, final LanguageInfo language) {
      // no-op
    }

    @Override
    public void onContextClosed(final TruffleContext context) {
      final ContextStats stats = contexts.remove(context);
      if (stats != null) {
        System.out.println("onContextClosed(): " + stats.report());
      }
    }


    public void updateStats() throws IOException {
      final String instanceId = INSTANCE_ID.getValue(env.getOptions());
      final long heapLimit = HEAP_LIMIT.getValue(env.getOptions());
      for (final Entry<TruffleContext, ContextStats> entry: this.contexts.entrySet()) {
        final long heapSize = computeHeapSize(entry.getKey(), heapLimit);
        entry.getValue().updateMemory(heapSize);
        if (heapSize > (128 << 20)) {
          System.out.println(instanceId + " " + entry.getKey() + " over threshold " + humanSize(heapSize));
        }
      }
    }

    private ContextStats updateContextStats(final TruffleContext context) {
      final ContextStats stats = contexts.get(context);
      if (stats == null) return null;

      final long heapLimit = HEAP_LIMIT.getValue(env.getOptions());
      stats.updateMemory(computeHeapSize(context, heapLimit));
      return stats;
    }

    private long computeHeapSize(final TruffleContext context, final long heapLimit) {
      if (context.isClosed() || context.isCancelling()) return -1;
      final long computeLimit = heapLimit > 0 ? heapLimit : 256 << 20;
      return env.calculateContextHeapSize(context, computeLimit, stopped);
    }
  }

  private static final class ContextStats {
    private final long[] memory = new long[32];
    private long memoryMax = 0;
    private long index = 0;

    public void updateMemory(final long usage) {
      if (usage < 0) return;
      memory[(int)((index++) & (memory.length - 1))] = usage;
      memoryMax = Math.max(memoryMax, usage);
    }

    public String report() {
      final StringBuilder builder = new StringBuilder();
      builder.append("mem-usage: [");
      final int off = (index > memory.length) ? (int)(index - memory.length) : 0;
      final int mask = memory.length - 1;
      builder.append(humanSize(memory[off & mask]));
      for (int i = 1; i < memory.length; ++i) {
        builder.append(", ").append(humanSize(memory[(off + i) & mask]));
      }
      builder.append("] - max:").append(humanSize(memoryMax));
      return builder.toString();
    }
  }

  public static String humanSize(final long size) {
    if (size >= (1L << 60)) return String.format("%.2fEiB", (float) size / (1L << 60));
    if (size >= (1L << 50)) return String.format("%.2fPiB", (float) size / (1L << 50));
    if (size >= (1L << 40)) return String.format("%.2fTiB", (float) size / (1L << 40));
    if (size >= (1L << 30)) return String.format("%.2fGiB", (float) size / (1L << 30));
    if (size >= (1L << 20)) return String.format("%.2fMiB", (float) size / (1L << 20));
    if (size >= (1L << 10)) return String.format("%.2fKiB", (float) size / (1L << 10));
    return size > 0 ? size + "bytes" : "0";
  }
}
